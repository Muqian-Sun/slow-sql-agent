package com.slowsql.agent.diagnosis.tools.result;

import com.slowsql.agent.diagnosis.tools.ErrorCategory;

import com.slowsql.agent.diagnosis.tools.HintCatalog;

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
 *     strategy, row_hash_subtype, sampled_rows, rewritten_plan, original_plan,
 *     rewritten_rows_estimate, original_rows_estimate, rows_reduction_pct,
 *     original_latency_ms, rewritten_latency_ms, speedup_x
 *   pass + cursor_plan_validity:
 *     strategy, rewritten_plan, original_plan, rewritten_rows_estimate,
 *     original_rows_estimate, rows_reduction_pct, warnings, rewritten_latency_ms
 *     (cursor 路径只测改写 SQL — 它必然 ms 级 PK 区间扫, 测它安全; 不测原 SQL, 那是要绕开的对象)
 *
 *   fail + row_hash:
 *     strategy, reason (row_count_diff | content_mismatch | order_mismatch),
 *     row_hash_subtype, sampled_rows, first_diff_row_index, diff_row_count,
 *     message, hint, original_latency_ms, rewritten_latency_ms, speedup_x
 *   fail + cursor_plan_validity:
 *     strategy, reason (cursor_plan_invalid | missing_order_by | explain_returned_empty),
 *     rewritten_plan, warnings, message, hint
 *
 *   error:
 *     reason (syntax_error | internal_error | original_sql_unsafe | rewritten_sql_unsafe),
 *     message, hint
 *
 * 约定: message 描述"发生了什么"(具体细节), hint 描述"怎么修"(从 HintCatalog 按 reason 取).
 *
 * 关于 latency / speedup_x:
 *   - 仅 row_hash 路径有: 双跑时本来就要执行原 SQL + 改写 SQL, 顺手 nanoTime 包一下
 *   - 真行级耗时, 不是 EXPLAIN 估算. speedup_x = original_latency_ms / rewritten_latency_ms
 *   - DBA 实战里看改写"到底有没有效"的第一眼数字
 */
public record VerifyResult(
        String status,
        String strategy,
        String reason,
        String category,                // ErrorCategory.name() — 失败/error 时填, pass 时 null
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
        List<String> warnings,
        Long originalLatencyMs,         // 原 SQL 双跑实际执行耗时, 仅 row_hash 路径有
        Long rewrittenLatencyMs,        // 改写 SQL 双跑实际执行耗时, 仅 row_hash 路径有
        Double speedupX                 // = originalLatencyMs / rewrittenLatencyMs, e.g. 38.9 倍
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
        ErrorCategory cat = HintCatalog.categoryOf(reason);
        return new VerifyResult("error", null, reason,
                cat == null ? null : cat.name(),
                message,
                cat == null ? null : cat.hint(),
                null,
                null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /**
     * row_hash 通过后, 一并附 plan + reduction% + 真实双跑耗时 / speedup.
     * speedupX: originalLatencyMs / rewrittenLatencyMs. 双跑都很快(< 1ms)时可能为 null, 不强求.
     */
    public static VerifyResult passRowHash(String subtype, int sampledRows,
                                           List<PlanRow> rewrittenPlan,
                                           List<PlanRow> originalPlan,
                                           long rewrittenRowsEstimate,
                                           Long originalRowsEstimate,
                                           Double rowsReductionPct,
                                           Long originalLatencyMs,
                                           Long rewrittenLatencyMs,
                                           Double speedupX) {
        return new VerifyResult("pass", "row_hash", null, null, null, null, subtype,
                sampledRows, null, null, rewrittenPlan, originalPlan,
                rewrittenRowsEstimate, originalRowsEstimate, rowsReductionPct,
                List.of(),
                originalLatencyMs, rewrittenLatencyMs, speedupX);
    }

    /**
     * message: 描述本次失败的具体细节(eg "original=N vs rewritten=M");
     * hint:    永远从 HintCatalog 取, 给 LLM 修复方向. 调用方不再传 hint, 避免把
     *          描述误塞进 hint 覆盖 catalog 的可操作建议.
     * latency: 哪怕失败也带上 — "改写很快但结果错"也是有价值的诊断信号.
     */
    public static VerifyResult failRowHash(String reason, String subtype, int sampledRows,
                                           Integer firstDiff, Integer diffCount, String message,
                                           Long originalLatencyMs,
                                           Long rewrittenLatencyMs,
                                           Double speedupX) {
        ErrorCategory cat = HintCatalog.categoryOf(reason);
        return new VerifyResult("fail", "row_hash", reason,
                cat == null ? null : cat.name(),
                message,
                cat == null ? null : cat.hint(),
                subtype, sampledRows, firstDiff, diffCount, null, null, null, null, null, null,
                originalLatencyMs, rewrittenLatencyMs, speedupX);
    }

    /**
     * rewrittenRows 一定有(改写 plan 必拿到);
     * originalRows / reductionPct 在拿不到原 SQL plan 时为 null —
     * 让 Jackson NON_NULL 跳过, 不要压成 0 误导 LLM "无改进".
     *
     * rewrittenLatencyMs: cursor 改写形态 (PK 区间扫 + LIMIT n) 必然 ms 级, 后端真跑一次顺手测;
     * 拿不到 (执行失败 / 跳过) 时传 null.
     * originalLatencyMs / speedupX 永远 null — cursor 路径不真测原 SQL, 那是要被绕开的对象,
     * 测它没意义且会卡 timeout.
     */
    public static VerifyResult passCursorPlan(List<PlanRow> rewrittenPlan,
                                              List<PlanRow> originalPlan,
                                              long rewrittenRows,
                                              Long originalRows,
                                              Double reductionPct,
                                              List<String> warnings,
                                              Long rewrittenLatencyMs) {
        return new VerifyResult("pass", "cursor_plan_validity", null, null, null, null, null,
                null, null, null, rewrittenPlan, originalPlan, rewrittenRows, originalRows,
                reductionPct, warnings,
                null, rewrittenLatencyMs, null);
    }

    /**
     * message: 描述具体硬伤(eg "table=orders 全表扫"); hint 永远从 catalog 取.
     */
    public static VerifyResult failCursorPlan(String reason, String message,
                                              List<PlanRow> rewrittenPlan,
                                              List<String> warnings) {
        ErrorCategory cat = HintCatalog.categoryOf(reason);
        return new VerifyResult("fail", "cursor_plan_validity", reason,
                cat == null ? null : cat.name(),
                message,
                cat == null ? null : cat.hint(),
                null, null, null, null, rewrittenPlan, null, null, null, null,
                warnings == null ? List.of() : warnings,
                null, null, null);
    }
}
