package com.slowsql.agent.diagnosis.tools;

import java.util.Map;

/**
 * 工具失败时给 LLM 的"类别 + 下一步动作"目录.
 *
 * 设计:
 *   - 把 raw reason 字符串归到 6 类 LLM-actionable 错误 (见 {@link ErrorCategory}) + 1 类 INTERNAL 兜底
 *   - 每类一条 hint, 不为每个 reason 重复定制 (复杂度收敛)
 *   - 结果 record 的工厂方法用 {@link #categoryOf(String)} / {@link #hintFor(String)} 自动填充
 *
 * reason → category 映射:
 *   - 只覆盖真实使用中可能出现的 reason 字符串 (与 record.reason() 字段保持一致)
 *   - 未知 reason 返回 null, 由调用方决定是否兜底为 INTERNAL
 *
 * 命名约定:
 *   - reason: snake_case 单词组 (来自工具内部 / 异常映射)
 *   - category: 大写下划线 (来自 ErrorCategory)
 */
public final class HintCatalog {

    private static final Map<String, ErrorCategory> REASON_TO_CATEGORY = Map.ofEntries(
            // getTableInfo
            Map.entry("not_found", ErrorCategory.SCHEMA_NOT_FOUND),
            Map.entry("invalid_identifier", ErrorCategory.SCHEMA_NOT_FOUND),

            // runExplain / SqlSafety / 输入参数
            Map.entry("safety_rejected", ErrorCategory.SAFETY_REJECTED),
            Map.entry("empty_sql", ErrorCategory.SAFETY_REJECTED),
            Map.entry("original_sql_unsafe", ErrorCategory.SAFETY_REJECTED),
            Map.entry("rewritten_sql_unsafe", ErrorCategory.SAFETY_REJECTED),

            // SQL 语法
            Map.entry("syntax_error", ErrorCategory.SYNTAX_ERROR),
            Map.entry("explain_returned_empty", ErrorCategory.SYNTAX_ERROR),

            // verify row_hash 失败
            Map.entry("row_count_diff", ErrorCategory.SEMANTIC_DIVERGENCE),
            Map.entry("content_mismatch", ErrorCategory.SEMANTIC_DIVERGENCE),

            // verify cursor_plan 失败 (排序)
            Map.entry("order_mismatch", ErrorCategory.UNSTABLE_ORDER),
            Map.entry("missing_order_by", ErrorCategory.UNSTABLE_ORDER),

            // verify cursor_plan 失败 (计划)
            Map.entry("cursor_plan_invalid", ErrorCategory.PLAN_UNHEALTHY),

            // 查询超时 — JDBC 设的 queryTimeout 触发, 与 internal 区分让 LLM 拿到正确指引
            Map.entry("query_timeout", ErrorCategory.QUERY_TIMEOUT),

            // 系统兜底
            Map.entry("internal_error", ErrorCategory.INTERNAL),
            Map.entry("json_serialize_fail", ErrorCategory.INTERNAL)
    );

    private HintCatalog() {}

    /**
     * reason → 类别. 未知 reason 返回 null (保留"未分类"状态, 调用方按需处理).
     */
    public static ErrorCategory categoryOf(String reason) {
        if (reason == null) return null;
        return REASON_TO_CATEGORY.get(reason);
    }

    /**
     * reason → 类别 hint. 未知 reason 返回 null, 与历史合约保持一致.
     */
    public static String hintFor(String reason) {
        ErrorCategory cat = categoryOf(reason);
        return cat == null ? null : cat.hint();
    }
}
