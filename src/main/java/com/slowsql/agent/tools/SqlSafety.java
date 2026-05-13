package com.slowsql.agent.tools;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工具层 SQL 安全校验.
 *
 * Agent 工具收到的 SQL 来自 LLM 推理或原始入参, 不可完全信任. 这一层是底线:
 *   1. 必须以 SELECT 或 WITH 开头(允许 CTE).
 *   2. 不允许 DDL / DML(UPDATE/DELETE/INSERT/...).
 *   3. 不允许多语句(分号后还有非空白内容).
 *
 * 注意: 这只是字符串层面的快速拦截, 真正的安全闸口仍在 DataSource 的 readOnly = true.
 * 但这一层能让 LLM 拿到更友好的 "safety_rejected" 错误信息, 走自纠正路径.
 */
public final class SqlSafety {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(UPDATE|DELETE|INSERT|REPLACE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|RENAME|LOCK|UNLOCK|CALL|HANDLER|LOAD|SET)\\b",
            Pattern.CASE_INSENSITIVE);

    private SqlSafety() {}

    /** 通过校验返回 null, 否则返回拒绝原因(供 LLM 阅读). */
    public static String rejectIfUnsafe(String sql) {
        if (sql == null || sql.isBlank()) {
            return "empty_sql";
        }
        String trimmed = sql.trim();

        // 去掉前导注释
        String stripped = stripLeadingComments(trimmed).trim();
        if (stripped.isEmpty()) {
            return "comment_only";
        }
        String head = stripped.substring(0, Math.min(stripped.length(), 8))
                .toUpperCase(Locale.ROOT);
        if (!head.startsWith("SELECT") && !head.startsWith("WITH")) {
            return "only_select_or_with_allowed";
        }
        if (FORBIDDEN.matcher(stripped).find()) {
            return "ddl_or_dml_not_allowed";
        }
        // 多语句: 末尾的分号允许, 但分号后不能有非空白内容
        int semi = stripped.indexOf(';');
        if (semi >= 0 && !stripped.substring(semi + 1).trim().isEmpty()) {
            return "multiple_statements_not_allowed";
        }
        return null;
    }

    /** 标识符校验: 字母/数字/下划线, 防注入. */
    public static boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static String stripLeadingComments(String sql) {
        String s = sql;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = nl < 0 ? "" : s.substring(nl + 1).trim();
                changed = true;
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2).trim();
                changed = true;
            }
        }
        return s;
    }
}
