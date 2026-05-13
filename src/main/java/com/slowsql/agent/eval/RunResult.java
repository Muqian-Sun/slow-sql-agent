package com.slowsql.agent.eval;

import com.slowsql.agent.agent.DiagnosisResult;

import java.util.List;
import java.util.Map;

/**
 * 单次 Agent run 的完整指标快照,聚合后产出 EvalReport.
 */
public record RunResult(
        String caseId,
        int iteration,
        DiagnosisResult diagnosis,         // run 失败时为 null

        // 效果层
        boolean outcomeMatched,
        double costReductionPercent,       // EXPLAIN rows 下降率 (0-1),来自 verify 工具的 reduction_pct
        boolean verificationPassed,
        String verificationStatus,         // EQUIVALENT / NOT_EQUIVALENT / NOT_APPLICABLE / NOT_CALLED
        boolean businessContextCompliant,  // 改写是否遵守 requirement 里的 API 修改性约束

        // 行为层
        int reactRounds,
        int totalToolCalls,
        int repeatedToolCalls,
        Map<String, Integer> toolFailuresByReason,

        // 资源消耗
        long totalTokens,
        long latencyMs,

        // 整 run 失败时填(其他字段保持默认)
        String error
) {

    /** 是否符合"高置信度一键放行"标准:置信度 ≥ 0.85 且 verify 通过 */
    public boolean isHighConfidence() {
        return diagnosis != null
                && diagnosis.confidence() >= 0.85
                && verificationPassed;
    }

    /** 是否含显式 assumptions(tie-breaker / 改 API 等场景必须有) */
    public boolean hasExplicitAssumptions() {
        return diagnosis != null
                && diagnosis.assumptions() != null
                && !diagnosis.assumptions().isEmpty();
    }

    public static RunResult error(String caseId, int iteration, long latencyMs, String message) {
        return new RunResult(
                caseId, iteration, null,
                false, 0, false, "ERROR", false,
                0, 0, 0, Map.of(),
                0, latencyMs, message);
    }

    public List<String> toolFailureReasonsList() {
        return toolFailuresByReason == null
                ? List.of()
                : List.copyOf(toolFailuresByReason.keySet());
    }
}
