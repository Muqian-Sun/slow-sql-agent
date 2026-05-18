package com.slowsql.agent.diagnosis.api;

/**
 * Agent 诊断产出的 outcome 类型, 对应 samples/golden_set.json 的 expected.expected_outcome.
 *
 * 仅 3 种 — agent 只做 SQL 层的两种深分页改写, 其它一切超出范围的情况(缺索引 /
 * 聚合 / 去重 / 副表排序 / DML / 嵌套子查询)统一归 unsupported, 具体方向性建议
 * (如索引 DDL / 物化视图 / OLAP)写到 additional_suggestions 里.
 *
 * 设计依据:
 *   1. 上游过滤保证输入一定是慢 SQL → 不需要 no_optimization_needed
 *   2. 加索引是 schema 改动, 不是 SQL 改写 → 不是独立 outcome, 归 unsupported + DDL 建议
 */
public enum OutcomeType {
    /** 改写为延迟关联(子查询取 PK + 外层 JOIN 反查) */
    REWRITTEN_DEFERRED_JOIN,
    /** 改写为游标分页(WHERE pk > last_id),需业务可改 API */
    REWRITTEN_CURSOR,
    /** 超出深分页 SQL 改写范围(缺索引 / 聚合 / 去重 / 副表排序 / DML 等),具体建议写到 additional_suggestions */
    UNSUPPORTED;

    /** JSON 里是 snake_case lowercase,这里做转换 */
    public String toJsonValue() {
        return name().toLowerCase();
    }

    public static OutcomeType fromJsonValue(String s) {
        return valueOf(s.toUpperCase());
    }
}
