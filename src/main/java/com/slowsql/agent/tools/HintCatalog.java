package com.slowsql.agent.tools;

import java.util.Map;

/**
 * 工具失败时给 LLM 的行动建议 catalog.
 *
 * 设计目标:
 *   - 把"工具失败后 LLM 该往哪个方向自纠正"显式化, 避免 LLM 看到 raw error message 后死循环重试.
 *   - 所有 reason → hint 集中一处, 三个 Result record 的工厂方法自动 fallback 到这里.
 *   - hint 是给 LLM 看的, 措辞要短 / 指方向 / 含可操作动词, 不写细节列表.
 *
 * 命名约定:
 *   reason 用 snake_case 单词组, 与 record.reason() 字段保持一致, 这样
 *   `HintCatalog.hintFor(record.reason())` 能稳定查到.
 */
public final class HintCatalog {

    private static final Map<String, String> HINTS = Map.ofEntries(

            // ---------- getTableInfo ----------
            Map.entry("invalid_identifier",
                    "表名/列名只能含字母数字下划线且 ≤64 字符. 检查参数拼写, 不要传带分号或空格的串."),
            Map.entry("not_found",
                    "对象不存在或不在当前 schema. 检查拼写, 不要猜可能的表名 — 优先看原 SQL 引用了哪张表."),

            // ---------- runExplain / SqlSafety ----------
            Map.entry("safety_rejected",
                    "工具只接 SELECT/WITH 单语句. 检查是否含 DML(UPDATE/DELETE/INSERT 等)或多条用分号串接的语句."),
            Map.entry("only_select_or_with_allowed",
                    "改写必须以 SELECT 或 WITH 开头, 不能是 DML 或其它语句."),
            Map.entry("ddl_or_dml_not_allowed",
                    "改写不能含 UPDATE/DELETE/INSERT/DROP 等 DDL/DML 关键字."),
            Map.entry("multiple_statements_not_allowed",
                    "改写只能是单条 SQL, 末尾分号可有可无, 但分号后不能再有内容."),
            Map.entry("empty_sql",
                    "传入了空 SQL, 检查参数."),

            // ---------- syntax / internal ----------
            Map.entry("syntax_error",
                    "SQL 语法错. cursor 改写最常见: 占位符 `?` 没填具体值(改成 0 之类的示例值, 在 assumptions 里说明占位语义)" +
                            "; 其它常见: 引号不匹配 / 列名表名拼写错."),
            Map.entry("internal_error",
                    "数据库内部错误, 不是改写本身的问题. 跳过这次工具调用, 用已有信息继续诊断."),

            // ---------- verifyResultEquivalence (fail) ----------
            Map.entry("original_sql_unsafe",
                    "你传的 originalSql 含非 SELECT 内容 — 这不正常, 检查是否传错了参数(应该传待诊断的原始 SQL)."),
            Map.entry("rewritten_sql_unsafe",
                    "你写的 rewrittenSql 含 DML 或多语句. 重写一个只读的 SELECT 改写."),
            Map.entry("row_count_diff",
                    "改写返回行数与原 SQL 不一致. 常见原因: WHERE 条件多/少, JOIN 类型变了(INNER vs LEFT), DISTINCT 被丢了."),
            Map.entry("content_mismatch",
                    "改写行内容与原 SQL 不一致. 常见原因: SELECT 列顺序/列数变了 / 改写漏了 WHERE 条件 / ORDER BY 列不一致."),
            Map.entry("order_mismatch",
                    "结果集相同但顺序不一致 → ORDER BY 不稳定. 在 ORDER BY 末尾追加 PK 作 tie-breaker."),
            Map.entry("missing_order_by",
                    "cursor 改写必须包含 ORDER BY 子句保证游标可重复. 加上 ORDER BY <游标列>."),
            Map.entry("cursor_plan_invalid",
                    "改写 plan 不健康(全表扫或未走索引). 修法: 让 WHERE 列命中索引 / 改用 PK 做游标谓词 / 不要在游标列上加函数."),
            Map.entry("explain_returned_empty",
                    "改写 SQL 的 EXPLAIN 输出为空, 通常是 SQL 本身不合法. 检查语法."),

            // ---------- JSON 序列化兜底 ----------
            Map.entry("json_serialize_fail",
                    "工具结果序列化失败, 不是你的问题. 用已有信息继续诊断.")
    );

    private HintCatalog() {}

    /** 查 hint, 没匹配返回 null(record 的 hint 字段就保持 null). */
    public static String hintFor(String reason) {
        if (reason == null) return null;
        return HINTS.get(reason);
    }
}
