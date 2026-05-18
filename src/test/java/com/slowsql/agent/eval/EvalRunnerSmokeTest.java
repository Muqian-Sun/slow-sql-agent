package com.slowsql.agent.eval;

import com.slowsql.agent.diagnosis.agent.StubDiagnosisAgent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke 测试 - 验证评测基础设施端到端跑通.
 */
class EvalRunnerSmokeTest {

    @Test
    void smokeEvalShouldCompleteAndWriteReport() throws Exception {
        EvalRunner runner = new EvalRunner(StubDiagnosisAgent::new);

        EvalConfig config = EvalConfig.smoke(
                Path.of("samples/golden_set.json"),
                Path.of("target/eval-reports")
        );

        EvalReport report = runner.run(config);

        assertThat(report.totalCases()).isEqualTo(5);
        assertThat(report.totalRuns()).isEqualTo(5);
        assertThat(report.p95LatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(report.caseDetails()).hasSize(5);

        System.out.println("======================================");
        System.out.println("Smoke eval report:");
        System.out.println("  cases:             " + report.totalCases());
        System.out.println("  runs:              " + report.totalRuns());
        System.out.println("  outcome_match:     " + pct(report.outcomeMatchRate()));
        System.out.println("  verify_pass:       " + pct(report.verificationPassRate()));
        System.out.println("  high_confidence:   " + pct(report.highConfidenceRate()));
        System.out.println("  assumptions_rate:  " + pct(report.assumptionsExplicitRate()));
        System.out.println("  avg_rounds:        " + report.avgReactRounds());
        System.out.println("  p95_latency_ms:    " + (long) report.p95LatencyMs());
        System.out.println("======================================");
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}
