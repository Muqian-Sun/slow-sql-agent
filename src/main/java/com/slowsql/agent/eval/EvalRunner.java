package com.slowsql.agent.eval;

import com.slowsql.agent.agent.BusinessContext;
import com.slowsql.agent.agent.DiagnosisAgent;
import com.slowsql.agent.agent.DiagnosisResult;
import com.slowsql.agent.agent.OutcomeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测主调度器.
 *
 * 流程:
 * 1. 加载 case(全量 / smoke / 指定 IDs)
 * 2. 每个 case 跑 N 次(防 LLM 采样波动)
 * 3. 聚合三层指标
 * 4. 生成 HTML 报告
 *
 * 关键设计:
 * - 多次跑取均值,这是 LLM agent 评测的硬要求(单次跑结果不可靠)
 * - 异常被捕获并记到 RunResult,不让一个 case 失败拖垮整个评测
 */
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final DiagnosisAgent agent;
    private final GoldenSetLoader loader;
    private final ReportGenerator reporter;

    public EvalRunner(DiagnosisAgent agent) {
        this.agent = agent;
        this.loader = new GoldenSetLoader();
        this.reporter = new ReportGenerator();
    }

    public EvalReport run(EvalConfig config) throws IOException {
        List<GoldenCase> cases = config.caseIds().isEmpty()
                ? loader.loadAll(config.goldenSetJson())
                : loader.loadByIds(config.goldenSetJson(), config.caseIds());

        log.info("Eval starting: {} cases × {} iterations, version={}",
                cases.size(), config.iterations(), config.promptVersion());

        Map<String, List<RunResult>> results = new LinkedHashMap<>();
        for (GoldenCase c : cases) {
            List<RunResult> runs = new ArrayList<>();
            for (int i = 0; i < config.iterations(); i++) {
                log.info("  Running case={} iter={}", c.id(), i);
                runs.add(runSingle(c, i));
            }
            results.put(c.id(), runs);
        }

        EvalReport report = EvalReport.aggregate(results, config);
        log.info("Eval completed: {} cases, p95_latency={}ms, outcome_match={}%, verify_pass={}%, high_conf={}%",
                report.totalCases(),
                (long) report.p95LatencyMs(),
                String.format("%.1f", report.outcomeMatchRate() * 100),
                String.format("%.1f", report.verificationPassRate() * 100),
                String.format("%.1f", report.highConfidenceRate() * 100));

        if (config.generateHtmlReport()) {
            var reportPath = reporter.writeHtml(report, config.reportOutputDir());
            log.info("HTML report written to: {}", reportPath);
        }

        return report;
    }

    private RunResult runSingle(GoldenCase c, int iteration) {
        long t0 = System.nanoTime();
        AgentStatsListener stats = new AgentStatsListener();
        // TODO 接 LangChain4j ChatModelListener 和 @Tool AOP 后填充 stats

        BusinessContext ctx = c.input().businessContext() != null
                ? c.input().businessContext()
                : BusinessContext.empty();

        try {
            DiagnosisResult diagnosis = agent.diagnose(c.input().sql(), ctx);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            boolean outcomeMatched = matchesExpected(diagnosis, c.expected());

            // TODO 接入真实 verify 工具(影子库双跑 + 行级 hash);此处用 confidence 模拟
            boolean verifyPassed = diagnosis.confidence() >= 0.85
                    && diagnosis.outcome() != OutcomeType.UNSUPPORTED;
            String verifyStatus = !verifyPassed && diagnosis.confidence() < 0.7
                    ? "UNDETERMINED"
                    : (verifyPassed ? "EQUIVALENT" : "NOT_EQUIVALENT");

            // TODO 接入真实 EXPLAIN cost 对比
            double costReduction = verifyPassed && diagnosis.rewrittenSql() != null
                    ? 0.75 : 0.0;

            return new RunResult(
                    c.id(), iteration, diagnosis,
                    outcomeMatched, costReduction, verifyPassed, verifyStatus,
                    stats.reactRounds(), stats.totalToolCalls(), stats.repeatedToolCalls(),
                    stats.terminatedByLimit(), stats.failuresByReason(),
                    stats.totalTokens(), elapsed,
                    null);

        } catch (Exception e) {
            log.error("  Run failed for case={}: {}", c.id(), e.getMessage());
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            return RunResult.error(c.id(), iteration, elapsed, e.getMessage());
        }
    }

    /** outcome 是否命中 expected / acceptable_outcomes */
    private boolean matchesExpected(DiagnosisResult diagnosis, GoldenCase.Expected expected) {
        if (diagnosis == null || expected == null || expected.expectedOutcome() == null) {
            return false;
        }
        String produced = diagnosis.outcome().toJsonValue();
        if (produced.equals(expected.expectedOutcome())) return true;
        if (expected.acceptableOutcomes() != null
                && expected.acceptableOutcomes().contains(produced)) {
            return true;
        }
        return false;
    }
}
