package com.slowsql.agent.agent;

/**
 * Agent 诊断产出的 outcome 类型,对应 samples/golden_set.json 的 expected.expected_outcome.
 */
public enum OutcomeType {
    /** 改写为延迟关联(子查询取 PK + 外层 JOIN 反查) */
    REWRITTEN_DEFERRED_JOIN,
    /** 改写为游标分页(WHERE pk > last_id),需业务可改 API */
    REWRITTEN_CURSOR,
    /** 给出索引建议,SQL 本身不改 */
    SUGGEST_INDEX,
    /** 识别 offset 不大不算深分页,无需优化 */
    NO_OPTIMIZATION_NEEDED,
    /** 超出本 Agent 能力范围,给方向性建议 */
    UNSUPPORTED;

    /** JSON 里是 snake_case lowercase,这里做转换 */
    public String toJsonValue() {
        return name().toLowerCase();
    }

    public static OutcomeType fromJsonValue(String s) {
        return valueOf(s.toUpperCase());
    }
}
