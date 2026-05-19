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
public class AgentStatsListener implements com.slowsql.agent.diagnosis.tools.ToolObserver {

    private int reactRounds = 0;
    private long totalTokens = 0;
    private final Map<String, Integer> toolCallCount = new HashMap<>();
    private final Map<String, Integer> sameParamRepeats = new HashMap<>();
    private final Map<String, Integer> failuresByReason = new HashMap<>();

    // verify 专用计数 — EvalRunner 用它判定 verifyPassed (替代之前混乱的 "没 verify_fail 失败 && 任意工具调过" 判定)
    private int verifyCallCount = 0;
    private int verifyPassCount = 0;
    private int verifyFailCount = 0;
    // 最近一次 verify PASS 拿到的 cost reduction%(cursor / deferred_join 都可能填), 用于 cost_reduction_median 真指标
    private Double lastVerifyReductionPct = null;
    // 最近一次 verify PASS 的 speedup_x (仅 row_hash 路径有真值, cursor 路径恒 null) — DBA 视角最直接的"改写有效性"信号
    private Double lastVerifySpeedupX = null;

    public void onLlmResponse(long tokens) {
        reactRounds++;
        totalTokens += tokens;
    }

    @Override
    public void onToolCall(String toolName, String argsFingerprint) {
        toolCallCount.merge(toolName, 1, Integer::sum);
        sameParamRepeats.merge(toolName + ":" + argsFingerprint, 1, Integer::sum);
    }

    @Override
    public void onToolFailure(String reason) {
        failuresByReason.merge(reason, 1, Integer::sum);
    }

    /**
     * verify 工具调用结果上报. pass=true 时累计 verifyPassCount, 并记录 reductionPct + speedupX (都可空).
     * 注: status='error' 的情况由 onToolFailure 单独走 — 不算 pass 也不算 fail.
     */
    @Override
    public void onVerifyResult(boolean pass, Double reductionPct, Double speedupX) {
        verifyCallCount++;
        if (pass) {
            verifyPassCount++;
            if (reductionPct != null) lastVerifyReductionPct = reductionPct;
            if (speedupX != null) lastVerifySpeedupX = speedupX;
        } else {
            verifyFailCount++;
        }
    }

    public int reactRounds() { return reactRounds; }
    public long totalTokens() { return totalTokens; }
    public int verifyCallCount() { return verifyCallCount; }
    public int verifyPassCount() { return verifyPassCount; }
    public int verifyFailCount() { return verifyFailCount; }
    public Double lastVerifyReductionPct() { return lastVerifyReductionPct; }
    public Double lastVerifySpeedupX() { return lastVerifySpeedupX; }

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


    public Map<String, Integer> failuresByReason() {
        return Map.copyOf(failuresByReason);
    }

    /**
     * 每个工具的调用次数明细 (toolName → count). 用于 EvalRunner 输出汇总日志 /
     * 失败 case 诊断 "是哪个工具撞了上限". 总数 = totalToolCalls.
     */
    public Map<String, Integer> toolCallCountByName() {
        return Map.copyOf(toolCallCount);
    }
}
