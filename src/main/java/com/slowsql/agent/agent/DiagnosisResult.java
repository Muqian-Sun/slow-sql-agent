package com.slowsql.agent.agent;

import java.util.List;

/**
 * Agent 诊断的最终产出,直接交给 DBA 复核.
 *
 * 设计要点:
 * - assumptions 必须显式声明(tie-breaker / 改 API 等改写假设),DBA 据此决定是否放行
 * - confidence 由 verify 结果决定 (高置信 ≥ 0.85 可一键放行,低置信需复核)
 * - additionalSuggestions 输出业务/架构层方向性建议(限翻页 / 缓存 / OLAP 等)
 */
public record DiagnosisResult(
        OutcomeType outcome,
        String rewrittenSql,                // outcome 是 unsupported / no_opt_needed 时为 null
        List<String> assumptions,           // 改写依赖的业务假设
        double confidence,                  // 0.0 - 1.0
        List<String> additionalSuggestions  // 不在 SQL 改写范畴内的方向性建议
) {
    public static DiagnosisResult unsupported(String reason, List<String> suggestions) {
        return new DiagnosisResult(
                OutcomeType.UNSUPPORTED, null, List.of(reason), 0.0, suggestions);
    }
}
