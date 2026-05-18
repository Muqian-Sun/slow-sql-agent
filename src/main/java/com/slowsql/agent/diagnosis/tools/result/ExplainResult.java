package com.slowsql.agent.diagnosis.tools.result;

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
) {
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
}
