package com.slowsql.agent.eval;

import com.slowsql.agent.agent.DiagnosisAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Token 降幅对照实验:
 *   1. 用相同 EvalConfig (同 case 列表 / 同 iter 数) 分别跑 layered 与 baseline 两组 Agent
 *   2. 配对 totalTokens, 输出 MemoryComparisonReport (per-case + 总体降幅)
 *   3. 落 JSON + 控制台表 — 对外文档与对照实验展示指向这份产物
 *
 * 用法:
 *   new MemoryComparisonRunner(
 *           () -> new LangChain4jDiagnosisAgent(cfg, backend),
 *           () -> LangChain4jDiagnosisAgent.withBaselineMemory(cfg, backend))
 *       .run(evalConfig);
 *
 * 为什么不复用 EvalRunner 的 EvalReport: token 对照需要严格按 caseId 配对、按 iter 取均值,
 * EvalReport 是单组聚合, 没法天然表达两组对照. 直接吃 rawRuns 自己 join.
 */
public class MemoryComparisonRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryComparisonRunner.class);

    private final Supplier<DiagnosisAgent> layeredFactory;
    private final Supplier<DiagnosisAgent> baselineFactory;

    public MemoryComparisonRunner(
            Supplier<DiagnosisAgent> layeredFactory,
            Supplier<DiagnosisAgent> baselineFactory) {
        this.layeredFactory = layeredFactory;
        this.baselineFactory = baselineFactory;
    }

    public MemoryComparisonReport run(EvalConfig config) throws IOException {
        log.info("Memory comparison starting: version={}, iterations={}",
                config.promptVersion(), config.iterations());

        // 用 EvalRunner 跑两遍 — 各自独立 stats / memory, 互不污染.
        // 关掉 HTML 报告: 对照实验只关心 rawRuns 里的 token 数据.
        EvalConfig silent = new EvalConfig(
                config.goldenSetJson(), config.caseIds(),
                config.iterations(), config.promptVersion(),
                config.comparedTo(), false, config.reportOutputDir());

        log.info("  [1/2] running LAYERED memory variant ...");
        EvalReport layeredReport = new EvalRunner(layeredFactory).run(silent);

        log.info("  [2/2] running BASELINE (MessageWindow) memory variant ...");
        EvalReport baselineReport = new EvalRunner(baselineFactory).run(silent);

        MemoryComparisonReport report = MemoryComparisonReport.from(
                config.promptVersion(),
                layeredReport.rawRuns(),
                baselineReport.rawRuns());

        if (config.generateHtmlReport()) {
            var jsonPath = report.writeJson(config.reportOutputDir());
            log.info("Memory comparison JSON written to: {}", jsonPath);
        }

        log.info("Memory comparison done: cases={}, overall_reduction={}%, median={}%",
                report.casesCompared(),
                String.format("%.1f", report.overallReductionPct() * 100),
                String.format("%.1f", report.medianReductionPct() * 100));

        return report;
    }
}
