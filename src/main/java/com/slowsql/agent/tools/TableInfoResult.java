package com.slowsql.agent.tools;

import java.util.List;

/**
 * getTableInfo 工具的结构化返回.
 *
 * 成功:
 *   {status:"ok", table, create_table, indexes:[{name,unique,columns,cardinality}],
 *    estimated_rows, row_count_note}
 *
 * 失败:
 *   {status:"error", reason, category, hint, table}
 *
 * category 与 hint 由 HintCatalog 按 reason 自动填:
 *   - category ∈ ErrorCategory 6 类 + INTERNAL 兜底
 *   - hint     给 LLM 行动建议
 */
public record TableInfoResult(
        String status,
        String reason,
        String category,
        String hint,
        String table,
        String createTable,
        List<IndexEntry> indexes,
        Long estimatedRows,
        String rowCountNote
) {
    public record IndexEntry(
            String name,
            boolean unique,
            List<String> columns,
            Long cardinality   // null 表示 InnoDB 未填(未 ANALYZE)
    ) {}

    public static TableInfoResult ok(String table, String createTable,
                                     List<IndexEntry> indexes, long estimatedRows) {
        return new TableInfoResult("ok", null, null, null, table, createTable, indexes, estimatedRows,
                "InnoDB approx, may deviate ±30% without ANALYZE");
    }

    public static TableInfoResult error(String reason, String table) {
        ErrorCategory cat = HintCatalog.categoryOf(reason);
        return new TableInfoResult("error", reason,
                cat == null ? null : cat.name(),
                cat == null ? null : cat.hint(),
                table, null, null, null, null);
    }

    public boolean isError() { return "error".equals(status); }

    public String toJson() { return ToolJson.toJson(this); }
}
