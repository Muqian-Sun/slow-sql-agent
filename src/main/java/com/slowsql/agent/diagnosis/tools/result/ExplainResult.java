package com.slowsql.agent.diagnosis.tools.result;

import com.slowsql.agent.diagnosis.memory.KeyFact;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.diagnosis.tools.ErrorCategory;

import com.slowsql.agent.diagnosis.tools.HintCatalog;

import java.util.List;
import java.util.Map;

/**
 * runExplain 工具的结构化返回.
 *
 * 成功: {status:"ok", rows:[{id, select_type, table, type, key, rows, Extra, ...}]}
 * 失败: {status:"error", reason, category, hint, message}
 *
 * category 与 hint 由 HintCatalog 按 reason 自动填 (6 类 + INTERNAL 兜底).
 * rows 用 Map<String,Object> 是因为 EXPLAIN 不同 MySQL 版本列略有差异, 用 Map 比死字段更鲁棒.
 */
public record ExplainResult(
        String status,
        String reason,
        String category,
        String hint,
        String message,
        List<Map<String, Object>> rows
) implements FactExportable {
    public static ExplainResult ok(List<Map<String, Object>> rows) {
        return new ExplainResult("ok", null, null, null, null, rows);
    }

    public static ExplainResult error(String reason, String message) {
        ErrorCategory cat = HintCatalog.categoryOf(reason);
        return new ExplainResult("error", reason,
                cat == null ? null : cat.name(),
                cat == null ? null : cat.hint(),
                message, null);
    }

    public boolean isError() { return "error".equals(status); }

    public String toJson() { return ToolJson.toJson(this); }

    @Override
    public void exportFactsTo(KeyFactStore store) {
        if (isError() || rows == null || rows.isEmpty()) return;

        StringBuilder detail = new StringBuilder();
        long totalRows = 0;
        for (Map<String, Object> r : rows) {
            String table = stringOf(r.get("table"));
            String type = stringOf(r.get("type"));
            String key = stringOf(r.get("key"));
            long n = longOf(r.get("rows"));
            String extra = stringOf(r.get("Extra"));
            if (extra.isBlank()) extra = stringOf(r.get("extra"));   // 不同 MySQL 版本大小写不同
            totalRows += Math.max(n, 0);
            if (detail.length() > 0) detail.append(" | ");
            detail.append(table).append(":").append(type)
                    .append("/").append(key.isBlank() ? "-" : key);
            if (n > 0) detail.append(",rows=").append(compactNum(n));
            if (!extra.isBlank()) detail.append(",extra=").append(extra);
        }
        if (totalRows > 0) {
            detail.append(" [total_rows=").append(compactNum(totalRows)).append(']');
        }
        // last_explain 多次会覆盖, 与之前 FactExtractor 行为一致
        store.put(KeyFact.plan("last_explain", detail.toString()));
    }

    private static String stringOf(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long longOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static String compactNum(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fG", n / 1_000_000_000.0);
    }
}
