package com.slowsql.agent.agent;

/**
 * 诊断 Agent 主接口 - 基于 LangChain4j AiServices + ReAct 多轮工具调用.
 */
public interface DiagnosisAgent {

    /**
     * 诊断慢 SQL 并产出改写方案.
     *
     * @param sql     待诊断的慢 SQL
     * @param context 业务上下文(可为 null,此时 Agent 显式假设)
     */
    DiagnosisResult diagnose(String sql, BusinessContext context);
}
