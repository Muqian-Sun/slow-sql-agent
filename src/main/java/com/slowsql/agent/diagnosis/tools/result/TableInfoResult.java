package com.slowsql.agent.diagnosis.tools.result;

import com.slowsql.agent.diagnosis.memory.KeyFact;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.diagnosis.tools.ErrorCategory;

import com.slowsql.agent.diagnosis.tools.HintCatalog;

import java.util.ArrayList;
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
) implements FactExportable {
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

    @Override
    public void exportFactsTo(KeyFactStore store) {
        // 失败 / 没 table 名 → 没 schema fact 可导
        if (isError() || table == null || table.isBlank()) return;

        StringBuilder detail = new StringBuilder();
        // 主键 + 索引摘要
        String pk = null;
        List<String> indexBlurbs = new ArrayList<>();
        if (indexes != null) {
            for (IndexEntry idx : indexes) {
                String cols = idx.columns() == null ? null : String.join(",", idx.columns());
                if ("PRIMARY".equals(idx.name()) && cols != null) pk = cols;
                indexBlurbs.add(String.format("%s%s(%s)%s",
                        idx.name(),
                        idx.unique() ? "[U]" : "",
                        cols == null ? "?" : cols,
                        idx.cardinality() != null && idx.cardinality() > 0
                                ? "~" + FactFormat.compactNum(idx.cardinality()) : ""));
            }
        }
        if (pk != null) detail.append("pk=").append(pk).append("; ");
        if (estimatedRows != null && estimatedRows > 0) {
            detail.append("rows~").append(FactFormat.compactNum(estimatedRows)).append("; ");
        }
        if (!indexBlurbs.isEmpty()) detail.append("idx=").append(String.join(",", indexBlurbs));
        store.put(KeyFact.schema("table=" + table, detail.toString().trim()));
    }
}
