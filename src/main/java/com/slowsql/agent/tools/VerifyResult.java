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
 *     row_hash_subtype, sampled_rows, first_diff_row_index, diff_row_count, message, hint
 *   fail + cursor_plan_validity:
 *     strategy, reason (cursor_plan_invalid | missing_order_by | explain_returned_empty),
 *     rewritten_plan, warnings, message, hint
 *
 *   error:
 *     reason (syntax_error | internal_error | original_sql_unsafe | rewritten_sql_unsafe),
 *     message, hint
 *
 * 约定: message 描述"发生了什么"(具体细节), hint 描述"怎么修"(从 HintCatalog 按 reason 取).
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

    /**
     * message: 描述本次失败的具体细节(eg "original=N vs rewritten=M");
     * hint:    永远从 HintCatalog 取, 给 LLM 修复方向. 调用方不再传 hint, 避免把
     *          描述误塞进 hint 覆盖 catalog 的可操作建议.
     */
    public static VerifyResult failRowHash(String reason, String subtype, int sampledRows,
                                           Integer firstDiff, Integer diffCount, String message) {
        return new VerifyResult("fail", "row_hash", reason, message,
                HintCatalog.hintFor(reason),
                subtype, sampledRows, firstDiff, diffCount, null, null, null, null, null, null);
    }

    /**
     * rewrittenRows 一定有(改写 plan 必拿到);
     * originalRows / reductionPct 在拿不到原 SQL plan 时为 null —
     * 让 Jackson NON_NULL 跳过, 不要压成 0 误导 LLM "无改进".
     */
    public static VerifyResult passCursorPlan(List<PlanRow> rewrittenPlan,
                                              List<PlanRow> originalPlan,
                                              long rewrittenRows,
                                              Long originalRows,
                                              Double reductionPct,
                                              List<String> warnings) {
        return new VerifyResult("pass", "cursor_plan_validity", null, null, null, null,
                null, null, null, rewrittenPlan, originalPlan, rewrittenRows, originalRows,
                reductionPct, warnings);
    }

    /**
     * message: 描述具体硬伤(eg "table=orders 全表扫"); hint 永远从 catalog 取.
     */
    public static VerifyResult failCursorPlan(String reason, String message,
                                              List<PlanRow> rewrittenPlan,
                                              List<String> warnings) {
        return new VerifyResult("fail", "cursor_plan_validity", reason, message,
                HintCatalog.hintFor(reason),
                null, null, null, null, rewrittenPlan, null, null, null, null,
                warnings == null ? List.of() : warnings);
    }
}
