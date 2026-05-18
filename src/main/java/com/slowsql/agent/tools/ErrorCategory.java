package com.slowsql.agent.tools;

/**
 * 工具异常的六大类 + 一个系统兜底.
 *
 * 设计意图:
 *   - LLM 看到工具失败时, 拿到的不是裸 SQLException, 而是"类别 + 下一步动作"
 *   - 6 类覆盖深分页诊断场景里 LLM **极大概率会撞上**的错误形态;
 *     罕见的细分原因(比如 invalid_identifier / empty_sql) 归到对应大类共用 hint, 不单独定制
 *   - INTERNAL 是系统级兜底(JDBC 内部 / 序列化失败), 不算 LLM-actionable 错误类
 *
 * hint 措辞约束:
 *   - 短 / 指方向 / 含可操作动词
 *   - 不替 LLM 推理, 只点出"该改什么 / 该看什么"
 */
public enum ErrorCategory {

    /** 表/列不存在或名字非法 — getTableInfo / EXPLAIN 时 LLM 把名字写错或猜错. */
    SCHEMA_NOT_FOUND(
            "对象不存在或名字非法. 检查原 SQL 引用了哪张表/哪些列, 不要凭名字猜或乱改大小写. " +
            "如果 SQL 里就是这个名字, 说明那是个空表或 schema 没建, 标 unsupported 并请业务确认."),

    /** SQL 解析失败 — cursor 改写忘填占位符 / 引号不匹配 / 列名拼写错. */
    SYNTAX_ERROR(
            "SQL 解析失败. cursor 改写最常见: 游标谓词的占位符 `?` / `${last_id}` 没填具体值 " +
            "(改成 `0` 之类示例值, 在 assumptions 里说明占位语义); " +
            "其它常见: 引号不匹配 / 列名表名拼写错."),

    /** 工具拒绝非 SELECT 输入 — agent 写了 DML / 多语句 / 空串, 或把 originalSql 与 rewrittenSql 调换. */
    SAFETY_REJECTED(
            "工具只接 SELECT/WITH 单语句, 输入被安全层拒绝. 检查: " +
            "rewrittenSql 不能含 UPDATE/DELETE/INSERT 等 DML; " +
            "不要传空串 / 多条用分号串接; " +
            "不要把 originalSql 与 rewrittenSql 参数调换."),

    /** 改写返回行集与原 SQL 不一致 — WHERE/JOIN/SELECT/DISTINCT 改了. */
    SEMANTIC_DIVERGENCE(
            "改写返回的行集与原 SQL 不一致. 常见根因: " +
            "WHERE 条件多/少, JOIN 类型变了(INNER vs LEFT), " +
            "SELECT 列顺序/列数变了, DISTINCT 被丢了. 对照原 SQL 逐项检查."),

    /** 排序不稳定或缺失 — deferred_join 没追加 PK tie-breaker, 或 cursor 改写没 ORDER BY. */
    UNSTABLE_ORDER(
            "ORDER BY 不稳定或缺失, 导致顺序漂移. 修法: " +
            "deferred_join: 在 ORDER BY 末尾追加 PK 作 tie-breaker; " +
            "cursor: 必须保留 ORDER BY 子句保证游标可重复."),

    /** 改写 plan 不健康 — cursor 改写没走索引 / 全表扫. */
    PLAN_UNHEALTHY(
            "改写 SQL 的 EXPLAIN 显示全表扫或未走索引. 修法: " +
            "让 WHERE 列命中索引 / 改用 PK 做游标谓词 / 不要在游标列上加函数 (例如 DATE(create_time))."),

    /**
     * 查询超时 — 原 SQL / 改写 SQL 在 SLOW_SQL_DB_QUERY_TIMEOUT_S 内未跑完.
     * 与 INTERNAL 区分: timeout 是改写"是否真省时间"的负反馈信号, 不是工具本身有 bug.
     */
    QUERY_TIMEOUT(
            "SQL 执行超过查询超时. 含义: 这条 SQL 在生产 DB 上一定也很慢. " +
            "对原 SQL 超时说明深分页确实病得重 (走 OFFSET 扫了大量行); " +
            "对改写 SQL 超时说明改写没把扫描量真正降下来, 检查是否走索引 / WHERE 是否真窄."),

    /** 系统兜底: JDBC 内部 / 序列化失败 / 其它非 LLM-actionable 错误. */
    INTERNAL(
            "工具内部错误, 不是改写本身的问题. 跳过这次工具调用, 用已有信息继续诊断.");

    private final String hint;

    ErrorCategory(String hint) {
        this.hint = hint;
    }

    public String hint() {
        return hint;
    }
}
