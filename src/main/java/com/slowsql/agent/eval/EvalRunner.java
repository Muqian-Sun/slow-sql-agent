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

            // verify 判定改成基于 stats 的真计数 (来源: DiagnosisTools 在每次 verify 工具调用后
            // 上报 onVerifyResult). 历史实现把 "任何工具调过 && 没 verify_fail" 当 pass + 混
            // confidence 进判定, 是错的, 已经全部移除.
            boolean verifyPassed = stats.verifyPassCount() > 0;
            String verifyStatus;
            if (diagnosis.outcome() == OutcomeType.UNSUPPORTED) {
                verifyStatus = "NOT_APPLICABLE";       // unsupported 不需要 verify
            } else if (stats.verifyCallCount() == 0) {
                verifyStatus = "NOT_CALLED";           // agent 没调 verify, 违反纪律
            } else if (verifyPassed) {
                verifyStatus = "EQUIVALENT";
            } else {
                verifyStatus = "NOT_EQUIVALENT";
            }

            // cost_reduction 接真值: verify PASS 时 stats 里有 lastVerifyReductionPct (单位 %),
            // 这里转成 0-1 比例. 没拿到 reduction (e.g. originalRows 估算缺失) 就保 0.
            double costReduction = 0.0;
            Double reductionPct = stats.lastVerifyReductionPct();
            if (verifyPassed && reductionPct != null) {
                costReduction = Math.max(0.0, reductionPct / 100.0);
            }

            boolean compliant = isBusinessContextCompliant(ctx.requirement(), diagnosis.outcome());

            // summarizer 触发次数: 非 LangChain4j 实现 / BASELINE 策略下恒为 0.
            int summarizerInvocations = (agent instanceof LangChain4jDiagnosisAgent l4jForMem)
                    ? l4jForMem.summarizerInvocations()
                    : 0;

            log.info("  done case={} outcome={} rounds={} tokens={} latency={}ms tools={} verify={}",
                    c.id(), diagnosis.outcome(), stats.reactRounds(), stats.totalTokens(),
                    elapsed, stats.toolCallCountByName(), verifyStatus);

            return new RunResult(
                    c.id(), iteration, diagnosis, c.expected().expectedOutcome(),
                    outcomeMatched, costReduction, verifyPassed, verifyStatus, compliant,
                    verifyPassed ? stats.lastVerifySpeedupX() : null,
                    stats.reactRounds(), stats.totalToolCalls(), stats.repeatedToolCalls(),
                    summarizerInvocations,
                    stats.failuresByReason(),
                    stats.totalTokens(), elapsed,
                    null);

        } catch (Exception e) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            // 失败 case 仍保留已累积 stats: 工具上限 / framework 上限触发前 LLM 已经跑了若干轮,
            // 把那些数据丢掉会让失败 case 完全黑盒, 无法诊断"是第几次工具调用炸的".
            AgentStatsListener stats = (agent instanceof LangChain4jDiagnosisAgent l4j) ? l4j.stats() : null;
            int summarizerInvocations = (agent instanceof LangChain4jDiagnosisAgent l4jForMem)
                    ? l4jForMem.summarizerInvocations() : 0;
            // 失败时把"撞墙前已调用的工具明细"放进日志, 比单看 e.getMessage() 信息密度高得多.
            if (stats != null) {
                log.error("  FAILED case={} after rounds={} tools={} tokens={} latency={}ms: {}",
                        c.id(), stats.reactRounds(), stats.toolCallCountByName(),
                        stats.totalTokens(), elapsed, e.getMessage());
            } else {
                log.error("  FAILED case={} latency={}ms: {}", c.id(), elapsed, e.getMessage());
            }
            return RunResult.errorWithStats(c.id(), iteration, c.expected().expectedOutcome(),
                    elapsed, e.getMessage(), stats, summarizerInvocations);
        }
    }

    /**
     * 业务上下文合规性判定: 只有 cursor 改写受约束(必须改 API), 其它 outcome 总合规.
     *
     * 规则:
     *   - outcome != REWRITTEN_CURSOR → 合规
     *   - requirement 缺失 → 按"保守不可改 API"假设, cursor 违反
     *   - requirement 含"不能改/不可改/接口不能动/传统翻页/page 参数" → 不允许改 API, cursor 违反
     *   - requirement 含"可改/前端可改/游标/下拉加载/无限滚动/无限下拉/last_id" → 允许, 合规
     *   - 两者都含 → 乐观判 允许(常见于 "可以改成游标" 这种描述)
     *   - 都不含 → 模糊, 按保守假设 cursor 违反
     */
    static boolean isBusinessContextCompliant(String requirement, OutcomeType outcome) {
        if (outcome != OutcomeType.REWRITTEN_CURSOR) return true;
        if (requirement == null || requirement.isBlank()) return false;

        boolean allowsApiChange = containsAny(requirement,
                "可改 API", "可改api", "可以改 API", "可以改api",
                "前端可改", "前端可以改",
                "游标", "下拉加载", "下拉无限", "无限滚动", "无限下拉",
                "传 last_id", "传游标", "last_id");
        if (allowsApiChange) return true;

        boolean forbidsApiChange = containsAny(requirement,
                "不能改", "不可改", "接口不能动",
                "传统翻页", "传统分页", "page 参数", "page/page_size");
        if (forbidsApiChange) return false;

        // 都不含 → 模糊, 按保守假设 cursor 违反
        return false;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
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
