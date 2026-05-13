package com.slowsql.agent.tools;

import java.util.List;

/**
 * verifyResultEquivalence 的结构化返回.
 *
 * status:    pass | fail | error
 * strategy:  row_hash | cursor_plan_validity
 *
 * 各组合下出现的字段(NON_NULL 序列化, 不相关字段不出现):
 *
 *   pass + row_hash:
 *     strategy, row_hash_subtype, sampled_rows, rewritten_plan
 *   pass + cursor_plan_validity:
 *     strategy, rewritten_plan, original_plan, rewritten_rows_estimate,
 *     original_rows_estimate, rows_reduction_pct, warnings
 *
 *   fail + row_hash:
 *     strategy, reason (row_count_diff | content_mismatch | order_mismatch),
 *     row_hash_subtype, sampled_rows, first_diff_row_index, diff_row_count, hint
 *   fail + cursor_plan_validity:
 *     strategy, reason (cursor_plan_invalid | missing_order_by | explain_empty),
 *     rewritten_plan, warnings, hint
 *
 *   error:
 *     reason (syntax_error | internal_error | original_sql_unsafe | rewritten_sql_unsafe), message
 */
public record VerifyResult(
        String status,
        String strategy,
        String reason,
        String message,
        String hint,
        String rowHashSubtype,          // deferred_join | general
        Integer sampledRows,
        Integer firstDiffRowIndex,
        Integer diffRowCount,
        List<PlanRow> rewrittenPlan,
        List<PlanRow> originalPlan,
        Long rewrittenRowsEstimate,
        Long originalRowsEstimate,
        Double rowsReductionPct,
        List<String> warnings
) {
    public record PlanRow(
            String table,
            String type,
            String key,
            Long rows,
            String extra
    ) {}

    public boolean isError() { return "error".equals(status); }
    public boolean isFail()  { return "fail".equals(status); }
    public boolean isPass()  { return "pass".equals(status); }

    public String toJson() { return ToolJson.toJson(this); }

    // ---------- 工厂方法 ----------

    public static VerifyResult error(String reason, String message) {
        return new VerifyResult("error", null, reason, message,
                HintCatalog.hintFor(reason), null,
                null, null, null, null, null, null, null, null, null);
    }

    public static VerifyResult passRowHash(String subtype, int sampledRows,
                                           List<PlanRow> rewrittenPlan) {
        return new VerifyResult("pass", "row_hash", null, null, null, subtype,
                sampledRows, null, null, rewrittenPlan, null, null, null, null, List.of());
    }

    public static VerifyResult failRowHash(String reason, String subtype, int sampledRows,
                                           Integer firstDiff, Integer diffCount, String hint) {
        return new VerifyResult("fail", "row_hash", reason, null,
                hint != null ? hint : HintCatalog.hintFor(reason),
                subtype, sampledRows, firstDiff, diffCount, null, null, null, null, null, null);
    }

    public static VerifyResult passCursorPlan(List<PlanRow> rewrittenPlan,
                                              List<PlanRow> originalPlan,
                                              long rewrittenRows, long originalRows,
                                              double reductionPct,
                                              List<String> warnings) {
        return new VerifyResult("pass", "cursor_plan_validity", null, null, null, null,
                null, null, null, rewrittenPlan, originalPlan, rewrittenRows, originalRows,
                reductionPct, warnings);
    }

    public static VerifyResult failCursorPlan(String reason, String hint,
                                              List<PlanRow> rewrittenPlan,
                                              List<String> warnings) {
        return new VerifyResult("fail", "cursor_plan_validity", reason, null,
                hint != null ? hint : HintCatalog.hintFor(reason),
                null, null, null, null, rewrittenPlan, null, null, null, null,
                warnings == null ? List.of() : warnings);
    }
}
