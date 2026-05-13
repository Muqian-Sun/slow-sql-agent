package com.slowsql.agent.agent.memory;

/**
 * 单条"关键事实" — 从工具返回里抽出来, 注入到 system prompt 末尾让 LLM 始终可见.
 *
 * 三段式约定:
 *   - category: 大类(schema / plan / verify), 决定 render 时的分组
 *   - subject:  具体对象的标识(table=orders, sql_fp=ab12cd34, rewrite=v1 等)
 *   - detail:   人读 + 机读两用的紧凑事实串, 不超过 200 字符
 *
 * 不可变 record, 由 KeyFactStore 管理生命周期.
 */
public record KeyFact(String category, String subject, String detail) {

    public static KeyFact schema(String subject, String detail) {
        return new KeyFact("schema", subject, detail);
    }

    public static KeyFact plan(String subject, String detail) {
        return new KeyFact("plan", subject, detail);
    }

    public static KeyFact verify(String subject, String detail) {
        return new KeyFact("verify", subject, detail);
    }
}
