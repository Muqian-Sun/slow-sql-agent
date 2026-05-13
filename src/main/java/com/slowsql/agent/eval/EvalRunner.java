package com.slowsql.agent.eval;

import com.slowsql.agent.agent.BusinessContext;
import com.slowsql.agent.agent.DiagnosisAgent;
import com.slowsql.agent.agent.DiagnosisResult;
import com.slowsql.agent.agent.LangChain4jDiagnosisAgent;
import com.slowsql.agent.agent.OutcomeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
 * - Agent 用 Supplier 注入: 每个 case 拿一个全新实例, AgentStatsListener 不跨 case 累加
 */
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final Supplier<DiagnosisAgent> agentFactory;
    private final GoldenSetLoader loader;
    private final ReportGenerator reporter;

    public EvalRunner(Supplier<DiagnosisAgent> agentFactory) {
        this.agentFactory = agentFactory;
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
        // 每个 case 拿全新 agent — AgentStatsListener 不跨 case 累加, 行为指标干净.
        DiagnosisAgent agent = agentFactory.get();

        BusinessContext ctx = c.input().requirement() != null
                ? BusinessContext.of(c.input().requirement())
                : BusinessContext.empty();

        try {
            DiagnosisResult diagnosis = agent.diagnose(c.input().sql(), ctx);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            // 真实 stats 从 LangChain4j 实现里取; 其它实现降级为空 stats.
            AgentStatsListener stats = (agent instanceof LangChain4jDiagnosisAgent l4j)
                    ? l4j.stats()
                    : new AgentStatsListener();

            boolean outcomeMatched = matchesExpected(diagnosis, c.expected());

            // 真实 verify 已由 verifyResultEquivalence 工具在 agent 内部完成;
            // 这里用 stats.failuresByReason 是否含 verify_fail 来判断本次是否最终通过 verify.
            boolean verifyToolCalled = stats.failuresByReason().keySet().stream()
                    .noneMatch(k -> k.contains("verify_fail"))
                    && stats.totalToolCalls() > 0;
            boolean verifyPassed = verifyToolCalled
                    && diagnosis.confidence() >= 0.85
                    && diagnosis.outcome() != OutcomeType.UNSUPPORTED;
            String verifyStatus;
            if (diagnosis.outcome() == OutcomeType.UNSUPPORTED) {
                verifyStatus = "NOT_APPLICABLE";
            } else if (verifyPassed) {
                verifyStatus = "EQUIVALENT";
            } else if (diagnosis.confidence() < 0.7) {
                verifyStatus = "UNDETERMINED";
            } else {
                verifyStatus = "NOT_EQUIVALENT";
            }

            // TODO 接入真实 EXPLAIN cost 对比; 暂用 confidence 近似.
            double costReduction = verifyPassed && diagnosis.rewrittenSql() != null
                    ? diagnosis.confidence() * 0.9
                    : 0.0;

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
