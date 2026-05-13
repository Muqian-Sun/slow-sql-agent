package com.slowsql.agent.tools;

/**
 * 工具数据源抽象 — 决定 DiagnosisTools 真去查 MySQL 还是用桩.
 *
 * 这层抽象的意义:
 *   - 单测 / 评测离线模式: 用 MockToolBackend, 不依赖真实 DB.
 *   - 真实诊断: 用 JdbcToolBackend, 连本地或 shadow DB.
 *   - 工具结果都是结构化 record, DiagnosisTools 负责序列化成 JSON 字符串发给 LLM.
 *
 * 返回 record 而非 String 的好处:
 *   - DiagnosisTools 可以从 record 字段提取 status / failure_reason 落到 stats,
 *     不需要去 parse 字符串拼起来的 "ERROR: xxx" / "FAIL: xxx".
 *   - record 保留类型, 后续接 EXPLAIN cost 对比 / shadow DB 切换都在 record 字段维度演进.
 */
public interface ToolBackend {

    /** 返回 CREATE TABLE 语句 + 索引摘要 + 行数估算. */
    TableInfoResult describeTable(String tableName);

    /** 返回 EXPLAIN 结果(结构化行列表). */
    ExplainResult explain(String sql);

    /**
     * 校验改写正确性, 按改写形态自动分流:
     *   - 改写仍含 OFFSET(deferred_join 形态)或两边都无 OFFSET → 行级 hash 等价比对.
     *   - 原 SQL 含 OFFSET 但改写消除了 OFFSET(cursor 形态)→ 校验改写 plan 健康性,
     *     不做行级等价(cursor 与 OFFSET 查询本质不等价).
     */
    VerifyResult verifyEquivalence(String originalSql, String rewrittenSql);
}
