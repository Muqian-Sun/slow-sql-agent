package com.slowsql.agent.tools;

/**
 * 工具调用次数硬上限触发. 单 case 内累计调用超过 DiagnosisTools.LIMIT_* 时由工具方法抛出,
 * 经 AiServices.toolExecutionErrorHandler 重抛后冒泡到 LangChain4jDiagnosisAgent.diagnose(),
 * 直接中断当前诊断, 而非让 LLM 继续重试 — 与 LangChain4j maxSequentialToolsInvocations 类似的硬熔断.
 */
public class ToolCallLimitExceededException extends RuntimeException {

    private final String toolName;
    private final int limit;

    public ToolCallLimitExceededException(String toolName, int limit) {
        super("Tool call limit exceeded: " + toolName + " > " + limit);
        this.toolName = toolName;
        this.limit = limit;
    }

    public String toolName() { return toolName; }
    public int limit() { return limit; }
}
