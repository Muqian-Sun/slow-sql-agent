package com.slowsql.agent.eval.memcmp;

import com.slowsql.agent.eval.RunResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合算法单测 — 不依赖 LLM, 用伪造 RunResult 列表验证:
 *   - 配对算法按 caseId
 *   - 多 iter 取均值
 *   - 任一侧 error → case 被 skip
 *   - 总体降幅 = 1 - sum(layered) / sum(baseline)
 */
class MemoryComparisonReportTest {

    @Test
    void aggregatesPerCaseTokenAveragesAcrossIterations() {
        // case_A: layered iter0=100, iter1=200 (avg=150) ; baseline iter0=300, iter1=400 (avg=350)
        // case_B: layered iter0=80           (avg=80)   ; baseline iter0=120           (avg=120)
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 3),
                runOk("case_A", 1, 200, 4),
                runOk("case_B", 0, 80, 2));
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 300, 3),
                runOk("case_A", 1, 400, 4),
                runOk("case_B", 0, 120, 2));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.casesCompared()).isEqualTo(2);
        assertThat(report.casesSkipped()).isZero();
        assertThat(report.perCase()).hasSize(2);

        var caseA = report.perCase().stream()
                .filter(c -> c.caseId().equals("case_A")).findFirst().orElseThrow();
        assertThat(caseA.avgLayeredTokens()).isEqualTo(150.0);
        assertThat(caseA.avgBaselineTokens()).isEqualTo(350.0);
        // reduction = 1 - 150/350 ≈ 0.5714
        assertThat(caseA.reductionPct()).isCloseTo(1.0 - 150.0 / 350.0, within(1e-9));

        // 总体降幅: 总 layered = 150+80=230, 总 baseline = 350+120=470
        // overall = 1 - 230/470 ≈ 0.5106
        assertThat(report.overallReductionPct()).isCloseTo(1.0 - 230.0 / 470.0, within(1e-9));
    }

    @Test
    void erroredCaseStillCountsTowardsReductionWhenTokensPresent() {
        // 新语义: 失败 case 已有 errorWithStats 累计的真实 token, 也进 reduction% 加权.
        // 体现"上下文增长到失败为止"的真实开销, 不被静默剔除.
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 3),
                runErrWithTokens("case_B", 0, "Tool call limit exceeded", 500));
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 200, 3),
                runOk("case_B", 0, 250, 3));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        // case_B 进 perCase, 但 failedSide 标 "layered"
        assertThat(report.casesCompared()).isEqualTo(2);
        var b = report.perCase().stream()
                .filter(c -> c.caseId().equals("case_B")).findFirst().orElseThrow();
        assertThat(b.failedSide()).isEqualTo("layered");
        assertThat(b.avgLayeredTokens()).isEqualTo(500.0);
        assertThat(b.avgBaselineTokens()).isEqualTo(250.0);
        // reduction = 1 - 500/250 = -1.0  (layered 失败前烧了 2× baseline 的 token)
        assertThat(b.reductionPct()).isCloseTo(-1.0, within(1e-9));

        // 仍出现在 skippedDetails 里给读者诊断
        assertThat(report.skippedDetails()).hasSize(1);
        assertThat(report.skippedDetails().get(0).caseId()).isEqualTo("case_B");
        assertThat(report.skippedDetails().get(0).failedSide()).isEqualTo("layered");

        // overall reduction 包括 case_B 的 token: sumL = 100+500=600, sumB = 200+250=450
        // overall = 1 - 600/450 = -0.333
        assertThat(report.overallReductionPct()).isCloseTo(1.0 - 600.0 / 450.0, within(1e-9));
    }

    @Test
    void erroredCaseWithZeroTokensStillSkipped() {
        // 老 RunResult.error() 工厂 totalTokens=0 — 这种 case 还是 skip (无 token 数据可对比)
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 3),
                runErr("case_B", 0, "llm timeout"));   // totalTokens=0
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 200, 3),
                runOk("case_B", 0, 250, 3));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.casesCompared()).isEqualTo(1);
        // case_B 的 token=0 → skip 仍然合理, 进 skippedDetails 但不进 perCase
        assertThat(report.skippedCases()).contains("case_B");
        assertThat(report.perCase()).noneMatch(c -> c.caseId().equals("case_B"));
    }

    @Test
    void skipsCaseWhenBaselineMissing() {
        List<RunResult> layered = List.of(runOk("case_A", 0, 100, 2));
        List<RunResult> baseline = List.of(); // baseline 没跑这个 case

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.casesCompared()).isZero();
        assertThat(report.casesSkipped()).isEqualTo(1);
        assertThat(report.skippedCases()).containsExactly("case_A");
    }

    @Test
    void negativeReductionMeansLayeredCostMore() {
        // 故意造一个 layered 比 baseline 还贵的 case — 算法不该崩, 报告里直接显示负 reduction
        List<RunResult> layered = List.of(runOk("case_A", 0, 500, 5));
        List<RunResult> baseline = List.of(runOk("case_A", 0, 200, 3));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        var c = report.perCase().get(0);
        assertThat(c.reductionPct()).isCloseTo(1.0 - 500.0 / 200.0, within(1e-9)); // -1.5
        assertThat(report.overallReductionPct()).isLessThan(0);
    }

    @Test
    void skipDetailRecordsWhichSideFailedAndWhy() {
        // case_A: layered 撞 tool_call_limit; baseline 跑通
        // case_B: 两侧都撞 framework 上限
        // case_C: 只有 layered 跑过, baseline 没运行
        List<RunResult> layered = List.of(
                runErr("case_A", 0, "Tool call limit exceeded: verifyResultEquivalence > 3"),
                runErr("case_B", 0, "Something is wrong, exceeded 30 sequential tool invocations"),
                runOk("case_C", 0, 100, 2));
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 200, 3),
                runErr("case_B", 0, "Something is wrong, exceeded 30 sequential tool invocations"));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.skippedDetails()).hasSize(3);

        var a = report.skippedDetails().stream()
                .filter(d -> d.caseId().equals("case_A")).findFirst().orElseThrow();
        assertThat(a.failedSide()).isEqualTo("layered");
        assertThat(a.reason()).isEqualTo("tool_call_limit");

        var b = report.skippedDetails().stream()
                .filter(d -> d.caseId().equals("case_B")).findFirst().orElseThrow();
        assertThat(b.failedSide()).isEqualTo("both");
        assertThat(b.reason()).contains("max_sequential_tools");

        var c = report.skippedDetails().stream()
                .filter(d -> d.caseId().equals("case_C")).findFirst().orElseThrow();
        assertThat(c.failedSide()).isEqualTo("no_baseline_run");

        // 派生 API 仍 work
        assertThat(report.skippedCases()).containsExactlyInAnyOrder("case_A", "case_B", "case_C");
    }

    @Test
    void outcomeMatrixCapturesLayeredOnlySuccessAsRescueSignal() {
        // 关键场景: 难 case 上 baseline 撞墙 / layered 跑通 — 这是 layered 设计的核心价值,
        // 但 reduction% 加权数字会把它整个剔除 (因为没 baseline tokens 配对).
        // OutcomeMatrix 必须把这种 case 显式记为 layered_only_succeeded.
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 2),                       // 两边都跑通
                runOk("case_B", 0, 200, 3),                       // layered 跑通
                runErr("case_C", 0, "Tool call limit exceeded"),  // layered 失败 (baseline 救场)
                runErr("case_D", 0, "boom"));                     // 两边都失败
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 200, 3),
                runErr("case_B", 0, "Tool call limit exceeded"),  // ← baseline 失败, layered 救场
                runOk("case_C", 0, 300, 4),
                runErr("case_D", 0, "boom"));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        var m = report.outcomeMatrix();
        assertThat(m.bothSucceeded()).isEqualTo(1);          // case_A
        assertThat(m.layeredOnlySucceeded()).isEqualTo(1);   // case_B (核心: layered 救场)
        assertThat(m.baselineOnlySucceeded()).isEqualTo(1);  // case_C (反向)
        assertThat(m.bothFailed()).isEqualTo(1);             // case_D
        assertThat(m.noBaselineRun()).isZero();
        assertThat(m.totalCases()).isEqualTo(4);
        // 成功率: layered 成功 case_A + case_B / 4 = 50%; baseline 成功 case_A + case_C / 4 = 50%
        assertThat(m.layeredSuccessRate()).isEqualTo(0.5);
        assertThat(m.baselineSuccessRate()).isEqualTo(0.5);
    }

    @Test
    void outcomeMatrixCountsNoBaselineRunSeparately() {
        // baseline 没跑某 case (配置错配) 不算 layered 单边成功, 单独归类
        List<RunResult> layered = List.of(runOk("case_X", 0, 100, 2));
        List<RunResult> baseline = List.of();
        var m = MemoryComparisonReport.from("v1", layered, baseline).outcomeMatrix();
        assertThat(m.bothSucceeded()).isZero();
        assertThat(m.layeredOnlySucceeded()).isZero();
        assertThat(m.noBaselineRun()).isEqualTo(1);
        // 成功率分母排除 noBaselineRun
        assertThat(m.layeredSuccessRate()).isZero();
    }

    @Test
    void stdDevReportsMultiIterSpread() {
        // 同 case 三次跑 token 差异很大 — stdDev 应非零
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 2),
                runOk("case_A", 1, 200, 2),
                runOk("case_A", 2, 300, 2));
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 150, 2),
                runOk("case_A", 1, 150, 2),
                runOk("case_A", 2, 150, 2)); // 完全一致 → stdDev=0

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        var c = report.perCase().get(0);
        assertThat(c.avgLayeredTokens()).isEqualTo(200.0);
        // 100/200/300 的样本 stdDev = sqrt(((-100)^2+0+100^2)/2) = sqrt(10000) = 100
        assertThat(c.layeredTokenStdDev()).isCloseTo(100.0, within(1e-6));
        assertThat(c.baselineTokenStdDev()).isZero();
    }

    @Test
    void medianReductionUsesPerCaseValuesNotWeighted() {
        // 三个 case 的 per-case reduction: 0.1 / 0.3 / 0.5 → 中位数 = 0.3
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 900, 2),   // 0.1
                runOk("case_B", 0, 700, 2),   // 0.3
                runOk("case_C", 0, 500, 2));  // 0.5
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 1000, 2),
                runOk("case_B", 0, 1000, 2),
                runOk("case_C", 0, 1000, 2));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.medianReductionPct()).isCloseTo(0.3, within(1e-9));
    }

    // ------------------------------------------------------------------

    private static RunResult runOk(String caseId, int iter, long tokens, int rounds) {
        return new RunResult(
                caseId, iter, null, "rewritten_deferred_join",
                true, 0, true, "EQUIVALENT", true, null,
                rounds, 0, 0, 0, Map.of(),
                tokens, 0, null);
    }

    private static RunResult runErr(String caseId, int iter, String err) {
        return RunResult.error(caseId, iter, "rewritten_deferred_join", 0, err);
    }

    /** 失败但带累计 token (模拟 errorWithStats 真实路径: LLM 撞墙前已经烧了 tokens). */
    private static RunResult runErrWithTokens(String caseId, int iter, String err, long tokens) {
        com.slowsql.agent.eval.AgentStatsListener stats = new com.slowsql.agent.eval.AgentStatsListener();
        stats.onLlmResponse(tokens);   // 一次性塞 tokens 到 stats
        return RunResult.errorWithStats(
                caseId, iter, "rewritten_deferred_join", 1000L, err, stats, 0);
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
