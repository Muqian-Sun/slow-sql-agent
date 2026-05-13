package com.slowsql.agent.eval;

import java.util.HashMap;
import java.util.Map;

/**
 * 单次 Agent run 期间的指标采集.
 *
 * 通过两个挂载点驱动:
 * - LangChain4j ChatModelListener 钩 LLM 响应(reactRounds / totalTokens)
 * - Spring AOP 切 @Tool 方法(toolCallCount / sameParamRepeats / failuresByReason)
 */
public class AgentStatsListener {

    private int reactRounds = 0;
    private long totalTokens = 0;
    private final Map<String, Integer> toolCallCount = new HashMap<>();
    private final Map<String, Integer> sameParamRepeats = new HashMap<>();
    private final Map<String, Integer> failuresByReason = new HashMap<>();
    private boolean terminatedByLimit = false;

    public void onLlmResponse(long tokens) {
        reactRounds++;
        totalTokens += tokens;
    }

    public void onToolCall(String toolName, String argsFingerprint) {
        toolCallCount.merge(toolName, 1, Integer::sum);
        sameParamRepeats.merge(toolName + ":" + argsFingerprint, 1, Integer::sum);
    }

    public void onToolFailure(String reason) {
        failuresByReason.merge(reason, 1, Integer::sum);
    }

    public void markTerminatedByLimit() {
        this.terminatedByLimit = true;
    }

    public int reactRounds() { return reactRounds; }
    public long totalTokens() { return totalTokens; }

    public int totalToolCalls() {
        return toolCallCount.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 同工具同参数被多次调用,超过 1 次的部分都算"重复" */
    public int repeatedToolCalls() {
        return sameParamRepeats.values().stream()
                .filter(c -> c > 1)
                .mapToInt(c -> c - 1)
                .sum();
    }

    public boolean terminatedByLimit() { return terminatedByLimit; }

    public Map<String, Integer> failuresByReason() {
        return Map.copyOf(failuresByReason);
    }
}
