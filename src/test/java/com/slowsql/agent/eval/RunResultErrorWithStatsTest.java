package com.slowsql.agent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败 case 仍保留已累积 stats 的工厂行为. 防回归: 工具上限触发时 EvalRunner 抛异常后,
 * RunResult 必须仍能反映"在抛之前 LLM 跑了多少轮 / 调了多少工具", 否则失败 case 黑盒.
 */
class RunResultErrorWithStatsTest {

    @Test
    void errorWithStatsCarriesAccumulatedCounts() {
        AgentStatsListener stats = new AgentStatsListener();
        stats.onLlmResponse(123);
        stats.onLlmResponse(456);
        stats.onToolCall("getTableInfo", "fp-a");
        stats.onToolCall("getTableInfo", "fp-a"); // repeated
        stats.onToolCall("verifyResultEquivalence", "fp-b");
        stats.onToolFailure("verify_fail");

        RunResult r = RunResult.errorWithStats(
                "case_x", 0, "rewritten_deferred_join",
                5000L, "Tool call limit exceeded: verifyResultEquivalence > 3",
                stats, /*summarizerInvocations*/ 0);

        assertThat(r.caseId()).isEqualTo("case_x");
        assertThat(r.error()).contains("Tool call limit exceeded");
        assertThat(r.verificationStatus()).isEqualTo("ERROR");
        assertThat(r.latencyMs()).isEqualTo(5000L);

        // 关键回归点: 已累积的 stats 反映在 RunResult 里
        assertThat(r.reactRounds()).isEqualTo(2);
        assertThat(r.totalTokens()).isEqualTo(579L);
        assertThat(r.totalToolCalls()).isEqualTo(3);
        assertThat(r.repeatedToolCalls()).isEqualTo(1);
        assertThat(r.toolFailuresByReason()).containsEntry("verify_fail", 1);
    }

    @Test
    void errorWithStatsFallsBackToZerosOnNullStats() {
        RunResult r = RunResult.errorWithStats(
                "case_y", 1, "unsupported", 100L, "boom",
                /*stats*/ null, 0);

        assertThat(r.reactRounds()).isZero();
        assertThat(r.totalToolCalls()).isZero();
        assertThat(r.totalTokens()).isZero();
        assertThat(r.error()).isEqualTo("boom");
    }

    @Test
    void legacyErrorFactoryStillProducesZeros() {
        // 老 error 工厂不接收 stats, 保持向后兼容
        RunResult r = RunResult.error("case_z", 0, "rewritten_cursor", 200L, "fail");
        assertThat(r.reactRounds()).isZero();
        assertThat(r.totalToolCalls()).isZero();
        assertThat(r.error()).isEqualTo("fail");
    }
}
