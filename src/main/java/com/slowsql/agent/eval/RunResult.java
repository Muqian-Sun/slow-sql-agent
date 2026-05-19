package com.slowsql.agent.eval;

import com.slowsql.agent.diagnosis.api.DiagnosisResult;

import java.util.List;
import java.util.Map;

/**
 * 单次 Agent run 的完整指标快照,聚合后产出 EvalReport.
 */
public record RunResult(
        String caseId,
        int iteration,
        DiagnosisResult diagnosis,         // run 失败时为 null
        String expectedOutcome,            // 该 case 期望的 outcome (供 EvalReport 拆 rewrite_precision / unsupported_recall)

        // 效果层
        boolean outcomeMatched,
        double costReductionPercent,       // EXPLAIN rows 下降率 (0-1),来自 verify 工具的 reduction_pct
        boolean verificationPassed,
        String verificationStatus,         // EQUIVALENT / NOT_EQUIVALENT / NOT_APPLICABLE / NOT_CALLED
        boolean businessContextCompliant,  // 改写是否遵守 requirement 里的 API 修改性约束
        Double speedupX,                   // verify row_hash 路径的真实双跑加速倍数;cursor / 无 verify 为 null

        // 行为层
        int reactRounds,
        int totalToolCalls,
        int repeatedToolCalls,
        int summarizerInvocations,         // LlmHistorySummarizer 实际触发次数 — 0 表示分层上下文未压缩
        Map<String, Integer> toolFailuresByReason,

        // 资源消耗
        long totalTokens,
        long latencyMs,

        // 整 run 失败时填(其他字段保持默认)
        String error
) {

    /** 是否含显式 assumptions(tie-breaker / 改 API 等场景必须有) */
    public boolean hasExplicitAssumptions() {
        return diagnosis != null
                && diagnosis.assumptions() != null
                && !diagnosis.assumptions().isEmpty();
    }

    public static RunResult error(String caseId, int iteration, String expectedOutcome,
                                  long latencyMs, String message) {
        return new RunResult(
                caseId, iteration, null, expectedOutcome,
                false, 0, false, "ERROR", false, null,
                0, 0, 0, 0, Map.of(),
                0, latencyMs, message);
    }

    /**
     * 失败 case 工厂 — 保留抛异常之前已累积的 stats. 用于 EvalRunner.runSingle 的 catch 路径:
     * 工具上限 / framework 上限触发时, 仍能从 AgentStatsListener 读到 reactRounds /
     * totalToolCalls / totalTokens / 失败原因等观察值. 旧 {@link #error} 工厂全置 0
     * 会让失败 case 完全黑盒, 这条路径是显式补回 observability.
     */
    public static RunResult errorWithStats(
            String caseId, int iteration, String expectedOutcome, long latencyMs, String message,
            AgentStatsListener stats, int summarizerInvocations) {
        if (stats == null) {
            return error(caseId, iteration, expectedOutcome, latencyMs, message);
        }
        return new RunResult(
                caseId, iteration, null, expectedOutcome,
                false, 0, false, "ERROR", false, null,
                stats.reactRounds(), stats.totalToolCalls(), stats.repeatedToolCalls(),
                summarizerInvocations,
                stats.failuresByReason(),
                stats.totalTokens(), latencyMs, message);
    }

    public List<String> toolFailureReasonsList() {
        return toolFailuresByReason == null
                ? List.of()
                : List.copyOf(toolFailuresByReason.keySet());
    }
}
