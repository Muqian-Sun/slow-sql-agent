package com.slowsql.agent.diagnosis.agent;

import com.slowsql.agent.diagnosis.api.BusinessContext;
import com.slowsql.agent.diagnosis.api.DiagnosisAgent;
import com.slowsql.agent.diagnosis.api.DiagnosisResult;
import com.slowsql.agent.diagnosis.api.OutcomeType;

import com.slowsql.agent.diagnosis.memory.FactExtractor;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.diagnosis.memory.LayeredChatMemory;
import com.slowsql.agent.diagnosis.memory.ToolCallWindowChatMemory;
import com.slowsql.agent.diagnosis.memory.LlmHistorySummarizer;
import com.slowsql.agent.eval.AgentStatsListener;
import com.slowsql.agent.llm.ChatModelFactory;
import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.llm.StatsCollectingListener;
import com.slowsql.agent.diagnosis.tools.DiagnosisTools;
import com.slowsql.agent.dbinspect.ToolBackend;
import com.slowsql.agent.diagnosis.tools.ToolCallLimitExceededException;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于 LangChain4j AiServices 的 ReAct 诊断 Agent.
 *
 * 装配链路:
 *   ChatModel (OpenAI-compat, 接 MiMo / DeepSeek 等)
 *     ↳ ChatModelListener (StatsCollectingListener) — 记录每轮 token / 失败
 *     ↳ Tools (DiagnosisTools) — getTableInfo / runExplain / verifyResultEquivalence
 *     ↳ LayeredChatMemory — 四层上下文: 不可压缩指令 / 不可压缩诉求 / 历史摘要 / 当前轮
 *     ↳ DeepPaginationAdvisor 接口 — 由 AiServices 反射生成实现
 *
 * ChatMemory:
 *   LayeredChatMemory 替代 LangChain4j 默认 MessageWindow:
 *     - SystemMessage(指令) / UserMessage(诉求) 永远保留, 不动 system 内容.
 *     - 旧 ReAct 周期由 LlmHistorySummarizer 语义压缩成累积摘要(历史摘要层),
 *       而非直接丢弃, 保留行为脉络可见性.
 *     - 最近 K 个完整周期原样保留(当前轮层), 默认 K=3.
 *     - ToolExecutionResultMessage 进入 memory 时由 FactExtractor 解析 JSON,
 *       抽出紧凑事实到共享的 KeyFactStore(旁路, 不强占 prompt).
 *   KeyFactStore 在 LayeredChatMemory 和 DiagnosisTools 之间共享: memory 负责写,
 *   recallFacts 工具负责读(pull 而非 push).
 *   效果: token 不再随轮次线性增长; 早期工具结果有摘要 + 结构化事实双通道召回.
 *   生命周期: 每个 diagnose() 用新 memory + 新 KeyFactStore (每 case 独立, 无跨调用泄漏).
 *
 * 单次 diagnose() 期间内部统计独立, 评测时 EvalRunner 为每个 case 新建一个实例.
 */
public class LangChain4jDiagnosisAgent implements DiagnosisAgent {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jDiagnosisAgent.class);

    /** 工具调用参数日志预览长度上限. 超过截断 + 加 "…", 避免大 SQL 把日志撑爆. */
    private static final int TOOL_ARGS_PREVIEW_MAX = 160;

    /**
     * Memory 装配策略:
     *   LAYERED          - 默认四层 LayeredChatMemory + LlmHistorySummarizer (生产路径)
     *   BASELINE_WINDOW  - ToolCallWindowChatMemory 纯滑窗 (按工具调用数算), 用于 token 对照实验.
     *                      跟 LAYERED 同维度对照, 唯一变量是 layered 的 archive/summarizer/KeyFactStore.
     */
    public enum MemoryStrategy { LAYERED, BASELINE_WINDOW }

    /**
     * BASELINE 滑窗的"最近 K 次工具调用"上限. 跟 LayeredChatMemory.DEFAULT_KEEP_RECENT_TOOL_CALLS=3
     * 严格一致, 让两条路径在"recent 容量"上是 1:1 对照.
     *
     * 历史: 这里原本用 LangChain4j 自带 MessageWindowChatMemory(maxMessages=10),
     * parallel tool calling 下消息数不对应 cycle 数, baseline 在 dj_005/006 上实际只装 2-3 cycle,
     * 比 layered 更早失忆 — 这是个跟"memory 设计"无关的配置劣势, 让对照实验不公平.
     * 换成 ToolCallWindowChatMemory 后, 两侧容量按同一维度算, 唯一变量是 layered 的增强机制.
     */
    public static final int BASELINE_WINDOW_TOOL_CALLS = LayeredChatMemory.DEFAULT_KEEP_RECENT_TOOL_CALLS;

    /**
     * 单次 diagnose() 期间最多允许的连续工具调用次数. 超过即 LangChain4j 抛异常,
     * EvalRunner / DiagnoseController catch 后标 ERROR. 用以兜底"LLM 死循环"长尾.
     *
     * 取 30 = 正常实测上限 20 (dj_005 6 表 JOIN) + 50% 余量. 真死循环 case (cur_001 iter1)
     * 实测 82 轮, 30 上限能精确卡住. 简单 case 2-6 轮远未触底, 不影响正常路径.
     *
     * 依据: 22 case × 3 iter = 66 runs 分布 P95=18, P99=20, 极端=82.
     */
    public static final int MAX_SEQUENTIAL_TOOL_INVOCATIONS = 30;

    private final DeepPaginationAdvisor advisor;
    private final AgentStatsListener stats;
    private final ChatMemory chatMemory;
    private final MemoryStrategy memoryStrategy;
    private final com.slowsql.agent.tracing.TraceCollector trace;

    public LangChain4jDiagnosisAgent(LlmConfig llmConfig, ToolBackend toolBackend) {
        this(llmConfig, toolBackend, MemoryStrategy.LAYERED);
    }

    /** 对照实验入口: 切到 LangChain4j 自带滑窗 ChatMemory, 其余路径完全一致. */
    public static LangChain4jDiagnosisAgent withBaselineMemory(
            LlmConfig llmConfig, ToolBackend toolBackend) {
        return new LangChain4jDiagnosisAgent(
                llmConfig, toolBackend, MemoryStrategy.BASELINE_WINDOW);
    }

    private LangChain4jDiagnosisAgent(
            LlmConfig llmConfig, ToolBackend toolBackend, MemoryStrategy strategy) {
        this.memoryStrategy = strategy;
        this.stats = new AgentStatsListener();
        this.trace = new com.slowsql.agent.tracing.TraceCollector();
        ChatModel model = ChatModelFactory.build(
                llmConfig,
                List.of(new StatsCollectingListener(stats)));
        // 共享 KeyFactStore: memory 端在 FactExtractor 里写, tools 端在 recallFacts 里读.
        // BASELINE 路径不用 KeyFactStore, 但 DiagnosisTools 仍持有一个空 store 让 recallFacts
        // 工具继续可用 (baseline 调 recallFacts 永远拿空集 — 与"baseline 没有事实库"语义吻合).
        KeyFactStore factStore = new KeyFactStore();
        DiagnosisTools tools = new DiagnosisTools(toolBackend, stats, factStore, trace);
        this.chatMemory = buildMemory(strategy, factStore, model);
        this.advisor = AiServices.builder(DeepPaginationAdvisor.class)
                .chatModel(model)
                .tools(tools)
                .chatMemory(chatMemory)
                .maxSequentialToolsInvocations(MAX_SEQUENTIAL_TOOL_INVOCATIONS)
                // 可观测性: 每次工具调用前打一行 DEBUG, 让 ReAct 轨迹可重现 (不再依赖临时 stderr).
                // 生产环境默认 INFO 不开, 调试时 logback.xml 设 dev.langchain4j=DEBUG 或本类专门提到 DEBUG 即可看到.
                .beforeToolExecution(bte -> {
                    if (log.isDebugEnabled()) {
                        var req = bte.request();
                        log.debug("[tool→] {}({})", req.name(), abbrev(req.arguments()));
                    }
                })
                // LangChain4j 默认会把 @Tool 抛的异常 wrap 成结果再喂回 LLM, 导致 callCount 上限只是"软提示"。
                // 这里把 ToolCallLimitExceededException 重抛, 让它冒泡出 AiServices 主循环, 等同硬熔断。
                // 其他异常仍走默认 wrap 为错误结果, 保留 LLM 的 ReAct 自纠正机会。
                .toolExecutionErrorHandler((throwable, ctx) -> {
                    if (throwable instanceof ToolCallLimitExceededException tle) {
                        throw tle;
                    }
                    return ToolErrorHandlerResult.text(
                            throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                })
                .build();
    }

    /** 工具参数预览: 单行化 + 截断, 用于日志输出. */
    private static String abbrev(String s) {
        if (s == null) return "null";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > TOOL_ARGS_PREVIEW_MAX
                ? oneLine.substring(0, TOOL_ARGS_PREVIEW_MAX) + "…"
                : oneLine;
    }

    private static ChatMemory buildMemory(
            MemoryStrategy strategy, KeyFactStore factStore, ChatModel model) {
        return switch (strategy) {
            case LAYERED -> new LayeredChatMemory(
                    "diagnose-" + System.nanoTime(),
                    LayeredChatMemory.DEFAULT_KEEP_RECENT_TOOL_CALLS,
                    factStore, new FactExtractor(),
                    new LlmHistorySummarizer(model));
            case BASELINE_WINDOW -> new ToolCallWindowChatMemory(
                    "baseline-" + System.nanoTime(),
                    BASELINE_WINDOW_TOOL_CALLS);
        };
    }

    @Override
    public DiagnosisResult diagnose(String sql, BusinessContext context) {
        String userPrompt = renderUserPrompt(sql, context);
        String llmOutput = advisor.diagnose(userPrompt);
        return DiagnosisOutputParser.parse(llmOutput);
    }

    /** 暴露统计供 EvalRunner / 报告层读. */
    public AgentStatsListener stats() {
        return stats;
    }

    /** 暴露 chatMemory 给评测层观察累积事实数量等. */
    public ChatMemory chatMemory() {
        return chatMemory;
    }

    /** 暴露 TraceCollector 给 EvalRunner 跑完 case 后落 trace JSON. */
    public com.slowsql.agent.tracing.TraceCollector trace() {
        return trace;
    }

    public MemoryStrategy memoryStrategy() {
        return memoryStrategy;
    }

    /** LAYERED 策略下返回摘要器触发次数, BASELINE 策略下恒为 0. */
    public int summarizerInvocations() {
        return chatMemory instanceof LayeredChatMemory l ? l.summarizerInvocations() : 0;
    }

    private static String renderUserPrompt(String sql, BusinessContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 待诊断的慢 SQL ===\n").append(sql.trim()).append('\n');
        if (ctx != null && ctx.hasRequirement()) {
            sb.append("\n=== 业务说明 ===\n").append(ctx.requirement().trim()).append('\n');
        } else {
            sb.append("\n=== 业务说明 ===\n(未提供, 请在 assumptions 中显式声明保守假设)\n");
        }
        sb.append("\n请按系统消息中的纪律调用工具诊断, 完成后输出 JSON.");
        return sb.toString();
    }
}
