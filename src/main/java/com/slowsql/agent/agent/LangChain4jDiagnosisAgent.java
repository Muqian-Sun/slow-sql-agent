package com.slowsql.agent.agent;

import com.slowsql.agent.agent.memory.FactExtractor;
import com.slowsql.agent.agent.memory.KeyFactStore;
import com.slowsql.agent.agent.memory.LayeredChatMemory;
import com.slowsql.agent.eval.AgentStatsListener;
import com.slowsql.agent.llm.ChatModelFactory;
import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.llm.StatsCollectingListener;
import com.slowsql.agent.tools.DiagnosisTools;
import com.slowsql.agent.tools.ToolBackend;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

/**
 * 基于 LangChain4j AiServices 的 ReAct 诊断 Agent.
 *
 * 装配链路:
 *   ChatModel (OpenAI-compat, 接 MiMo / DeepSeek 等)
 *     ↳ ChatModelListener (StatsCollectingListener) — 记录每轮 token / 失败
 *     ↳ Tools (DiagnosisTools) — getTableInfo / runExplain / verifyResultEquivalence
 *     ↳ LayeredChatMemory — 三段式 ChatMemory: system+facts / user / 最近 3 cycles
 *     ↳ DeepPaginationAdvisor 接口 — 由 AiServices 反射生成实现
 *
 * ChatMemory (v1):
 *   LayeredChatMemory 替代 LangChain4j 默认 MessageWindow:
 *     - SystemMessage / UserMessage 永远保留, 不动 system 内容 — fact 改由 LLM 主动调
 *       recallFacts 工具拉取 (pull 而非 push), 这样 LLM 自己决定什么时候要 fact 摘要.
 *     - ToolExecutionResultMessage 进入 memory 时由 FactExtractor 解析 JSON, 抽出紧凑事实
 *       到共享的 KeyFactStore.
 *     - 旧 ReAct 周期整体丢弃 (按 cycle 截断, 默认保留最近 3 个).
 *   KeyFactStore 在 LayeredChatMemory 和 DiagnosisTools 之间共享: memory 负责写,
 *   recallFacts 工具负责读.
 *   效果: token 不再随轮次线性增长; LLM 想要 fact 摘要时调 recallFacts, 不想要就跳过.
 *   生命周期: 每个 diagnose() 用新 memory + 新 KeyFactStore (每 case 独立, 无跨调用泄漏).
 *
 * 单次 diagnose() 期间内部统计独立, 评测时 EvalRunner 为每个 case 新建一个实例.
 */
public class LangChain4jDiagnosisAgent implements DiagnosisAgent {

    private final DeepPaginationAdvisor advisor;
    private final AgentStatsListener stats;
    private final LayeredChatMemory chatMemory;

    public LangChain4jDiagnosisAgent(LlmConfig llmConfig, ToolBackend toolBackend) {
        this.stats = new AgentStatsListener();
        ChatModel model = ChatModelFactory.build(
                llmConfig,
                List.of(new StatsCollectingListener(stats)));
        // 共享 KeyFactStore: memory 端在 FactExtractor 里写, tools 端在 recallFacts 里读.
        KeyFactStore factStore = new KeyFactStore();
        DiagnosisTools tools = new DiagnosisTools(toolBackend, stats, factStore);
        this.chatMemory = new LayeredChatMemory(
                "diagnose-" + System.nanoTime(),
                LayeredChatMemory.DEFAULT_KEEP_CYCLES,
                factStore, new FactExtractor());
        this.advisor = AiServices.builder(DeepPaginationAdvisor.class)
                .chatModel(model)
                .tools(tools)
                .chatMemory(chatMemory)
                .build();
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
    public LayeredChatMemory chatMemory() {
        return chatMemory;
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
