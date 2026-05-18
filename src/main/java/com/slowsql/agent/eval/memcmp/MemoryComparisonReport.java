package com.slowsql.agent.eval.memcmp;

import com.slowsql.agent.eval.RunResult;

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
 * 同一 golden case 在 LayeredChatMemory(LAYERED) 与 ToolCallWindowChatMemory(BASELINE) 下
 * 的 token 占用对照. 用于证伪 / 证实"分层上下文较默认滑窗降低 token"的说法.
 *
 * 配对策略:
 *   - 按 caseId 聚合, 每个 case 取 iter=0..N 的 totalTokens 均值, 同时记录样本 stdDev,
 *     让读者能判断 reduction 数字是真信号还是 N 小时的采样噪声.
 *   - **失败 case 也进对照**: RunResult.errorWithStats 保留了"撞墙前累计 token",
 *     反映"上下文增长到失败为止"的真实开销, 计入 reduction% 加权. 同时记 SkipDetail
 *     (失败侧 / 分类原因) 让读者知道哪个 case 是失败状态参与对比.
 *   - 唯一必须 skip 的: baseline 根本没跑这个 case (no_baseline_run), 没数据可对比.
 *   - reductionPct = 1 - layeredTokens / baselineTokens; 正值表示 layered 更省 token.
 */
public record MemoryComparisonReport(
        String promptVersion,
        String runAt,                       // ISO-8601, 不引 jsr310 模块也能稳定序列化
        int casesCompared,
        int casesSkipped,
        double avgLayeredTokensPerCase,
        double avgBaselineTokensPerCase,
        double overallReductionPct,         // 加权平均: 总 layered token / 总 baseline token; 只看"两边都跑通"的子集
        double medianReductionPct,
        List<CaseComparison> perCase,
        List<SkipDetail> skippedDetails,
        OutcomeMatrix outcomeMatrix         // 难 case 救场信号: 把"layered 跑通 / baseline 失败"这种证据
                                            // 从 skippedDetails 提升为汇总数字, 不让加权 reduction% 独占叙事
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
            double reductionPct,
            String failedSide                   // null=两边都成功; "layered"/"baseline"/"both"=部分失败仍计入对照
                                                // 失败 case 的 token 是 errorWithStats 保留的"撞墙前累计",
                                                // 反映"上下文增长到失败为止"的真实开销, 该进加权数字.
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
     * 跨 case 的"哪一边成功 / 失败"分桶汇总. 解决 overallReductionPct 的盲区: 加权
     * 数字只反映"两边都跑通"的子集, 而"layered 救场" (baseline 失败 / layered 跑通)
     * 这种 layered 设计最强证据被静默剔除. 该矩阵把这条信号变成显式指标.
     *
     * 字段语义:
     *   - bothSucceeded:         两边都正常完成 (= casesCompared, perCase 数)
     *   - layeredOnlySucceeded:  layered 跑通但 baseline 失败 — **layered 救场, 设计价值的直接证据**
     *   - baselineOnlySucceeded: baseline 跑通但 layered 失败 — 反向证据, layered 在该 case 上反而更脆
     *   - bothFailed:            两边都失败 — case 自身复杂度上限, 与 memory 策略无关
     *   - noBaselineRun:         baseline 根本没跑这个 case (配置错配)
     */
    public record OutcomeMatrix(
            int bothSucceeded,
            int layeredOnlySucceeded,
            int baselineOnlySucceeded,
            int bothFailed,
            int noBaselineRun
    ) {
        public int totalCases() {
            return bothSucceeded + layeredOnlySucceeded + baselineOnlySucceeded + bothFailed + noBaselineRun;
        }

        /** layered 单测成功率 (排除 noBaselineRun, 那是配置问题不是 memory 问题). */
        public double layeredSuccessRate() {
            int denom = totalCases() - noBaselineRun;
            return denom == 0 ? 0.0 : (double) (bothSucceeded + layeredOnlySucceeded) / denom;
        }

        /** baseline 单测成功率 (同上). */
        public double baselineSuccessRate() {
            int denom = totalCases() - noBaselineRun;
            return denom == 0 ? 0.0 : (double) (bothSucceeded + baselineOnlySucceeded) / denom;
        }
    }

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

            // 唯一必须 skip 的情况: baseline 根本没跑这个 case, 没数据可对比
            if (bRuns == null) {
                skipped.add(new SkipDetail(caseId, "no_baseline_run", "baseline_did_not_run"));
                continue;
            }

            // 失败 case 也进对照: RunResult.errorWithStats 保留了"撞墙前累计 token",
            // 反映"上下文增长到失败为止"的真实开销 — 该进 reduction% 加权数字而不是被静默剔除.
            boolean lErr = hasAnyError(lRuns);
            boolean bErr = hasAnyError(bRuns);
            String failedSide = null;
            if (lErr || bErr) {
                failedSide = lErr && bErr ? "both" : (lErr ? "layered" : "baseline");
                String reason = lErr && bErr
                        ? "L=" + classifyError(firstError(lRuns)) + "; B=" + classifyError(firstError(bRuns))
                        : classifyError(firstError(lErr ? lRuns : bRuns));
                skipped.add(new SkipDetail(caseId, failedSide, reason));
                // 不 continue — 继续算 token 对比
            }

            double avgL = avgTokens(lRuns);
            double avgB = avgTokens(bRuns);
            // 真零 token (config error / 测试桩) 才 skip — 没法做除法
            if (avgB <= 0 || avgL <= 0) {
                if (failedSide == null) {
                    // 没有 errored, 但 token 是 0 — 异常情况, 单独标
                    skipped.add(new SkipDetail(caseId,
                            avgB <= 0 ? "baseline" : "layered", "tokens_zero"));
                }
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
                    reduction,
                    failedSide));
        }

        double overall = sumBaseline == 0 ? 0 : 1.0 - sumLayered / sumBaseline;
        int n = perCase.size();
        double avgLayeredPer = n == 0 ? 0 : sumLayered / n;
        double avgBaselinePer = n == 0 ? 0 : sumBaseline / n;

        // OutcomeMatrix: 把"哪边失败"从 skippedDetails 的文字诊断, 提升为分桶汇总数字
        int layeredOnly = 0, baselineOnly = 0, bothFailed = 0, noBase = 0;
        for (SkipDetail s : skipped) {
            switch (s.failedSide()) {
                case "baseline" -> layeredOnly++;            // baseline 失败 = layered 救场
                case "layered"  -> baselineOnly++;           // layered 失败 = baseline 救场 (反向)
                case "both"     -> bothFailed++;
                case "no_baseline_run" -> noBase++;
                default -> noBase++;
            }
        }
        OutcomeMatrix matrix = new OutcomeMatrix(n, layeredOnly, baselineOnly, bothFailed, noBase);

        return new MemoryComparisonReport(
                promptVersion, Instant.now().toString(),
                n, skipped.size(),
                avgLayeredPer, avgBaselinePer,
                overall, median(reductions),
                perCase, skipped, matrix);
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
            String marker = c.failedSide() == null ? "" : " [" + c.failedSide() + "_failed]";
            sb.append(String.format("%-22s %7.0f±%5.0f %7.0f±%5.0f %10.1f %10.1f %9.1f%%%s%n",
                    c.caseId(),
                    c.avgLayeredTokens(), c.layeredTokenStdDev(),
                    c.avgBaselineTokens(), c.baselineTokenStdDev(),
                    c.avgLayeredRounds(),
                    c.avgBaselineRounds(),
                    c.reductionPct() * 100,
                    marker));
        }
        sb.append("------------------------------------------------------------------\n");
        sb.append(String.format("avg per case        layered=%.0f  baseline=%.0f%n",
                avgLayeredTokensPerCase, avgBaselineTokensPerCase));
        sb.append(String.format("overall reduction   %.1f%% (weighted, includes errored cases)  /  median %.1f%%%n",
                overallReductionPct * 100, medianReductionPct * 100));

        // OutcomeMatrix: 把"哪边救场"从隐含信号变显式数字, 跟 reduction% 并列输出
        sb.append("------------------------------------------------------------------\n");
        sb.append(String.format("outcome matrix:  both_ok=%d  layered_only=%d  baseline_only=%d  both_failed=%d%s%n",
                outcomeMatrix.bothSucceeded(),
                outcomeMatrix.layeredOnlySucceeded(),
                outcomeMatrix.baselineOnlySucceeded(),
                outcomeMatrix.bothFailed(),
                outcomeMatrix.noBaselineRun() > 0
                        ? "  no_baseline=" + outcomeMatrix.noBaselineRun() : ""));
        sb.append(String.format("success rate:    layered=%.0f%%  baseline=%.0f%%  (delta=%+.0fpp)%n",
                outcomeMatrix.layeredSuccessRate() * 100,
                outcomeMatrix.baselineSuccessRate() * 100,
                (outcomeMatrix.layeredSuccessRate() - outcomeMatrix.baselineSuccessRate()) * 100));

        if (!skippedDetails.isEmpty()) {
            sb.append("skipped details:\n");
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
    public static String classifyError(String err) {
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
