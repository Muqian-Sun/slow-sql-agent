package com.slowsql.agent.eval;

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
    void skipsCaseWhenEitherSideErrors() {
        List<RunResult> layered = List.of(
                runOk("case_A", 0, 100, 3),
                runErr("case_B", 0, "llm timeout"));
        List<RunResult> baseline = List.of(
                runOk("case_A", 0, 200, 3),
                runOk("case_B", 0, 250, 3));

        MemoryComparisonReport report = MemoryComparisonReport.from("v1", layered, baseline);

        assertThat(report.casesCompared()).isEqualTo(1);
        assertThat(report.casesSkipped()).isEqualTo(1);
        assertThat(report.skippedCases()).containsExactly("case_B");
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

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
