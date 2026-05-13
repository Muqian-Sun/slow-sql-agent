package com.slowsql.agent.agent;

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
 *     ↳ DeepPaginationAdvisor 接口 — 由 AiServices 反射生成实现
 *
 * ChatMemory:
 *   不显式配置, 走 LangChain4j 默认行为 — 单次 diagnose() 调用内部全量保留所有 ReAct
 *   消息 (无窗口截断); 跨 diagnose() 调用不保留(每个 case 独立). 这是符合预期的:
 *   每个评测 case 是隔离上下文, 不需要历史记忆.
 *
 * 单次 diagnose() 期间内部统计独立, 评测时 EvalRunner 为每个 case 新建一个实例.
 */
public class LangChain4jDiagnosisAgent implements DiagnosisAgent {

    private final DeepPaginationAdvisor advisor;
    private final AgentStatsListener stats;

    public LangChain4jDiagnosisAgent(LlmConfig llmConfig, ToolBackend toolBackend) {
        this.stats = new AgentStatsListener();
        ChatModel model = ChatModelFactory.build(
                llmConfig,
                List.of(new StatsCollectingListener(stats)));
        DiagnosisTools tools = new DiagnosisTools(toolBackend, stats);
        this.advisor = AiServices.builder(DeepPaginationAdvisor.class)
                .chatModel(model)
                .tools(tools)
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
