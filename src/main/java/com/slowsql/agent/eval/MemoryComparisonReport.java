package com.slowsql.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * 同一 golden case 在 LayeredChatMemory(LAYERED) 与 MessageWindowChatMemory(BASELINE) 下
 * 的 token 占用对照. 用于证伪 / 证实"分层上下文较默认滑窗降低 token"的说法.
 *
 * 配对策略:
 *   - 按 caseId 聚合, 每个 case 取 iter=0..N 的 totalTokens 均值, 同时记录样本 stdDev,
 *     让读者能判断 reduction 数字是真信号还是 N 小时的采样噪声.
 *   - 任一侧错误 (RunResult.error != null) 整个 case 标 skipped, **但保留 SkipDetail**
 *     (失败侧 / 分类原因), 避免难 case 在加权降幅里被静默剔除而结论偏乐观.
 *   - reductionPct = 1 - layeredTokens / baselineTokens; 正值表示 layered 更省 token.
 */
public record MemoryComparisonReport(
        String promptVersion,
        String runAt,                       // ISO-8601, 不引 jsr310 模块也能稳定序列化
        int casesCompared,
        int casesSkipped,
        double avgLayeredTokensPerCase,
        double avgBaselineTokensPerCase,
        double overallReductionPct,         // 加权平均: 总 layered token / 总 baseline token
        double medianReductionPct,
        List<CaseComparison> perCase,
        List<SkipDetail> skippedDetails
) {

    public record CaseComparison(
            String caseId,
            double avgLayeredTokens,
            double avgBaselineTokens,
            double layeredTokenStdDev,          // 样本标准差, N=1 时为 0
            double baselineTokenStdDev,
            double avgLayeredRounds,
            double avgBaselineRounds,
            double avgSummarizerInvocations,    // 仅 layered 路径有意义
            double reductionPct
    ) {}

    /**
     * 单条 case 被剔除时的诊断信息. 比 List&lt;String&gt; 更细, 让读者知道:
     *   - 是哪一边失败 (layered 失败说明 LayeredChatMemory 在该 case 上反而更脆,
     *     baseline 失败说明默认滑窗在该 case 上撑不住), 而不是模糊的 "skipped"
     *   - 失败原因 (tool_call_limit / max_sequential_tools / 其它) — 关键看 cap 类失败占比
     */
    public record SkipDetail(
            String caseId,
            String failedSide,                  // "layered" / "baseline" / "both" / "no_baseline_run"
            String reason                       // 分类: tool_call_limit / max_sequential_tools / 其它前 80 字符
    ) {}

    /**
     * 从 layered / baseline 两次完整 evaluator run 的 rawRuns 聚合成对照报告.
     * runs 按 caseId 配对; 同 caseId 内多 iter 取均值, 抹平 LLM 采样波动.
     */
    public static MemoryComparisonReport from(
            String promptVersion,
            List<RunResult> layeredRuns,
            List<RunResult> baselineRuns) {

        Map<String, List<RunResult>> layeredByCase = groupByCase(layeredRuns);
        Map<String, List<RunResult>> baselineByCase = groupByCase(baselineRuns);

        List<CaseComparison> perCase = new ArrayList<>();
        List<SkipDetail> skipped = new ArrayList<>();
        double sumLayered = 0;
        double sumBaseline = 0;
        List<Double> reductions = new ArrayList<>();

        for (Map.Entry<String, List<RunResult>> e : layeredByCase.entrySet()) {
            String caseId = e.getKey();
            List<RunResult> lRuns = e.getValue();
            List<RunResult> bRuns = baselineByCase.get(caseId);

            if (bRuns == null) {
                skipped.add(new SkipDetail(caseId, "no_baseline_run", "baseline_did_not_run"));
                continue;
            }
            boolean lErr = hasAnyError(lRuns);
            boolean bErr = hasAnyError(bRuns);
            if (lErr || bErr) {
                String side = lErr && bErr ? "both" : (lErr ? "layered" : "baseline");
                String reason = lErr && bErr
                        ? "L=" + classifyError(firstError(lRuns)) + "; B=" + classifyError(firstError(bRuns))
                        : classifyError(firstError(lErr ? lRuns : bRuns));
                skipped.add(new SkipDetail(caseId, side, reason));
                continue;
            }

            double avgL = avgTokens(lRuns);
            double avgB = avgTokens(bRuns);
            if (avgB <= 0) {
                skipped.add(new SkipDetail(caseId, "baseline", "baseline_tokens_zero"));
                continue;
            }

            double reduction = 1.0 - avgL / avgB;
            sumLayered += avgL;
            sumBaseline += avgB;
            reductions.add(reduction);

            perCase.add(new CaseComparison(
                    caseId,
                    avgL, avgB,
                    stdDev(lRuns, RunResult::totalTokens),
                    stdDev(bRuns, RunResult::totalTokens),
                    avg(lRuns, RunResult::reactRounds),
                    avg(bRuns, RunResult::reactRounds),
                    avg(lRuns, RunResult::summarizerInvocations),
                    reduction));
        }

        double overall = sumBaseline == 0 ? 0 : 1.0 - sumLayered / sumBaseline;
        int n = perCase.size();
        double avgLayeredPer = n == 0 ? 0 : sumLayered / n;
        double avgBaselinePer = n == 0 ? 0 : sumBaseline / n;

        return new MemoryComparisonReport(
                promptVersion, Instant.now().toString(),
                n, skipped.size(),
                avgLayeredPer, avgBaselinePer,
                overall, median(reductions),
                perCase, skipped);
    }

    /** 派生: 历史 caller 仍按 List&lt;String&gt; 消费. 新代码用 skippedDetails 拿完整信息. */
    public List<String> skippedCases() {
        return skippedDetails.stream().map(SkipDetail::caseId).toList();
    }

    /** 落 JSON 到 target/eval-reports/memory-comparison-{version}-{ts}.json */
    public Path writeJson(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String ts = runAt.replaceAll("[:.]", "-");
        Path out = outputDir.resolve("memory-comparison-" + promptVersion + "-" + ts + ".json");
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(out, mapper.writeValueAsString(this));
        return out;
    }

    /** 打印一份对齐的控制台表, 方便 IT 跑完直接看. */
    public String renderConsoleTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n══════════════════════════════════════════════════════════════════\n");
        sb.append(String.format("Memory Comparison — version=%s, cases=%d, skipped=%d%n",
                promptVersion, casesCompared, casesSkipped));
        sb.append("══════════════════════════════════════════════════════════════════\n");
        sb.append(String.format("%-22s %14s %14s %10s %10s %10s%n",
                "case_id", "layered_tok±σ", "baseline_tok±σ", "rounds_L", "rounds_B", "reduction"));
        sb.append("------------------------------------------------------------------\n");
        for (CaseComparison c : perCase) {
            sb.append(String.format("%-22s %7.0f±%5.0f %7.0f±%5.0f %10.1f %10.1f %9.1f%%%n",
                    c.caseId(),
                    c.avgLayeredTokens(), c.layeredTokenStdDev(),
                    c.avgBaselineTokens(), c.baselineTokenStdDev(),
                    c.avgLayeredRounds(),
                    c.avgBaselineRounds(),
                    c.reductionPct() * 100));
        }
        sb.append("------------------------------------------------------------------\n");
        sb.append(String.format("avg per case        layered=%.0f  baseline=%.0f%n",
                avgLayeredTokensPerCase, avgBaselineTokensPerCase));
        sb.append(String.format("overall reduction   %.1f%% (weighted)  /  median %.1f%%%n",
                overallReductionPct * 100, medianReductionPct * 100));
        if (!skippedDetails.isEmpty()) {
            sb.append("skipped:\n");
            for (SkipDetail s : skippedDetails) {
                sb.append(String.format("  %s  side=%s  reason=%s%n",
                        s.caseId(), s.failedSide(), s.reason()));
            }
        }
        sb.append("══════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static Map<String, List<RunResult>> groupByCase(List<RunResult> runs) {
        Map<String, List<RunResult>> out = new LinkedHashMap<>();
        if (runs == null) return out;
        for (RunResult r : runs) {
            out.computeIfAbsent(r.caseId(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static boolean hasAnyError(List<RunResult> runs) {
        return runs.stream().anyMatch(r -> r.error() != null);
    }

    private static String firstError(List<RunResult> runs) {
        return runs.stream().map(RunResult::error).filter(s -> s != null).findFirst().orElse(null);
    }

    /** 失败原因归类, 让 SkipDetail 里的 reason 字段在多 case 间可统计. */
    static String classifyError(String err) {
        if (err == null) return "unknown";
        if (err.contains("Tool call limit exceeded")) return "tool_call_limit";
        if (err.contains("sequential tool invocations")) return "max_sequential_tools";
        int max = Math.min(80, err.length());
        return err.substring(0, max).replace('\n', ' ').trim();
    }

    private static double avgTokens(List<RunResult> runs) {
        return runs.stream().mapToLong(RunResult::totalTokens).average().orElse(0);
    }

    private static double avg(List<RunResult> runs, java.util.function.ToIntFunction<RunResult> f) {
        return runs.stream().mapToInt(f).average().orElse(0);
    }

    /** 样本标准差 (Bessel 校正). N ≤ 1 时返回 0. */
    private static double stdDev(List<RunResult> runs, ToLongFunction<RunResult> f) {
        int n = runs.size();
        if (n <= 1) return 0;
        double mean = runs.stream().mapToLong(f).average().orElse(0);
        double sumSq = 0;
        for (RunResult r : runs) {
            double d = f.applyAsLong(r) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }
}
