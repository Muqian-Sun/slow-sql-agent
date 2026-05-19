package com.slowsql.agent.diagnosis.tools;

/**
 * 工具调用的 observability 接口 — DiagnosisTools 跟"谁在收集 stats"解耦.
 *
 * 历史: DiagnosisTools 直接依赖 eval/AgentStatsListener (评测层具体类), 是个反向依赖 —
 * 工具层不该知道评测层的存在. 引入本接口后, DiagnosisTools 只对 ToolObserver 编程,
 * 评测层 (AgentStatsListener) 实现这个接口, Spring 入口 / IT / 任何不需要 eval 包的链路
 * 都可以提供自己的 Observer.
 *
 * 实现约定:
 *   - 所有方法**不应抛**: observer 是旁路, 异常会让主回路的工具调用 fail.
 *   - 调用顺序: onToolCall (任何路径) → onToolFailure (失败时) / onVerifyResult (verify 特有).
 *   - 失败语义跟 HintCatalog reason 一致 — 让 observer 想做聚合时跟 ErrorCategory 对得上.
 */
public interface ToolObserver {

    /** 每次 @Tool 方法被调用时上报 — 不管成功/失败. argsFingerprint 是 SHA-1 短指纹. */
    void onToolCall(String toolName, String argsFingerprint);

    /** 工具调用失败时上报具体原因 (HintCatalog 已知 reason 或 internal_error). */
    void onToolFailure(String reason);

    /**
     * verify 工具独有: 上报每次 verify 的 pass/fail + 真实指标.
     * reductionPct 与 speedupX 在 pass 且工具能算出时非 null, 其它情况可能为 null.
     */
    void onVerifyResult(boolean pass, Double reductionPct, Double speedupX);

    /** No-op 实现, 给不关心 observability 的链路 (mock/单测) 用. */
    ToolObserver NO_OP = new ToolObserver() {
        @Override public void onToolCall(String toolName, String argsFingerprint) {}
        @Override public void onToolFailure(String reason) {}
        @Override public void onVerifyResult(boolean pass, Double reductionPct, Double speedupX) {}
    };
}
