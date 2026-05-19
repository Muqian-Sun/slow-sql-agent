package com.slowsql.agent.eval;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测聚合报告 - 三层指标 + Case 详情 + 异常矩阵.
 *
 * 三层指标设计:
 * 1. 业务价值层
 * 2. 效果层(改写质量)
 * 3. 行为层(Agent 内部健康度)
 */
public record EvalReport(
        String promptVersion,
        Instant runAt,
        int totalCases,
        int totalRuns,

        // ─── 第 1 层:业务价值 ───
        double p95LatencyMs,
        double tokenReductionVsBaseline,    // 与 baseline 对比的 token 降幅,需配 comparedTo

        // ─── 第 2 层:效果 ───
        double outcomeMatchRate,
        double rewritePrecision,          // 仅 rewrite 期望的 case 上的命中率, 含 acceptable_outcomes 兜底
                                          // (例如 dj_006 期望 deferred_join, acceptable 含 unsupported,
                                          //  LLM 标 unsupported 也算 match — 宽松判定)
        double rewritePrecisionStrict,    // 同上但 strict: 只看 outcome == expected_outcome, acceptable 不参与
                                          // 与 rewritePrecision 的 delta 暴露"保守标 unsupported 蒙对"的样本占比
        double unsupportedRecall,         // 仅 unsupported 期望的 case 上的召回率, 看越界识别能力
        double verificationPassRate,
        double verificationUndeterminedRate,
        double costReductionMedian,
        double speedupMedian,             // verify row_hash 路径真实双跑加速中位数 (倍数, 1.0 = 没加速)
        double speedupMax,                // 最大加速倍数, 体现头部 case 改写效果
        double businessContextCompliance,
        double assumptionsExplicitRate,

        // ─── 第 3 层:行为 ───
        double avgReactRounds,
        int maxReactRounds,
        double repeatedToolCallRate,
        double avgSummarizerInvocations,    // 历史摘要器在评测里平均触发次数, 体现分层上下文是否真的"工作"
        Map<String, Integer> toolFailuresByReason,

        // ─── 详细数据 ───
        List<CaseAggregate> caseDetails,
        List<RunResult> rawRuns
) {

    public record CaseAggregate(
            String caseId,
            int passCount,
            int totalRuns,
            String mostCommonOutcome,
            double avgLatencyMs,
            double avgTokens,
            double avgReactRounds,
            List<String> failureNotes
    ) {
        public double passRate() {
            return totalRuns == 0 ? 0 : (double) passCount / totalRuns;
        }
    }

    public static EvalReport aggregate(Map<String, List<RunResult>> resultsPerCase, EvalConfig config) {
        List<RunResult> all = resultsPerCase.values().stream()
                .flatMap(List::stream).toList();
        int totalRuns = all.size();
        if (totalRuns == 0) {
            return empty(config);
        }

        double avgRounds = all.stream().mapToInt(RunResult::reactRounds).average().orElse(0);
        int maxRounds = all.stream().mapToInt(RunResult::reactRounds).max().orElse(0);

        long[] sortedLatency = all.stream().mapToLong(RunResult::latencyMs).sorted().toArray();
        double p95 = percentile(sortedLatency, 0.95);

        double outcomeMatch = ratio(all, RunResult::outcomeMatched);
        double verifyPass = ratio(all, RunResult::verificationPassed);
        double verifyUndet = ratio(all, r -> "UNDETERMINED".equals(r.verificationStatus()));
        double assumptionsRate = ratio(all, RunResult::hasExplicitAssumptions);

        // 按 expected 分桶: rewrite_precision (期望 rewrite 的 case 命中率) + unsupported_recall
        // (期望 unsupported 的 case 命中率). 拆开避免总命中率被 unsupported 占比稀释/抬升.
        double rewritePrec = ratioWhere(all,
                r -> r.expectedOutcome() != null && !"unsupported".equals(r.expectedOutcome()),
                RunResult::outcomeMatched);
        // strict: 只看 outcome == expected_outcome, 不让 acceptable_outcomes 兜底.
        // delta = rewritePrec - rewritePrecStrict 就是"保守标 unsupported 蒙对"的样本占比.
        double rewritePrecStrict = ratioWhere(all,
                r -> r.expectedOutcome() != null && !"unsupported".equals(r.expectedOutcome()),
                r -> r.diagnosis() != null
                        && r.expectedOutcome().equals(r.diagnosis().outcome().toJsonValue()));
        double unsupRecall = ratioWhere(all,
                r -> "unsupported".equals(r.expectedOutcome()),
                RunResult::outcomeMatched);

        // cost_reduction_median 只统计 verify 真通过的样本 — unsupported / errored / NOT_CALLED
        // 的 costReductionPercent=0 会把 median 拉到底部, 失去"改写带来的成本降幅 median"统计含义.
        double[] sortedCost = all.stream()
                .filter(RunResult::verificationPassed)
                .mapToDouble(RunResult::costReductionPercent)
                .sorted()
                .toArray();
        double costMed = median(sortedCost);

        // speedup 只在 verify row_hash 通过的 case 里有真值, 其余 null 跳过
        double[] sortedSpeedup = all.stream()
                .map(RunResult::speedupX)
                .filter(s -> s != null && !s.isNaN() && !s.isInfinite())
                .mapToDouble(Double::doubleValue).sorted().toArray();
        double speedupMed = median(sortedSpeedup);
        double speedupMax = sortedSpeedup.length == 0 ? 0 : sortedSpeedup[sortedSpeedup.length - 1];

        int totalCalls = all.stream().mapToInt(RunResult::totalToolCalls).sum();
        int totalRepeats = all.stream().mapToInt(RunResult::repeatedToolCalls).sum();
        double repeatRate = totalCalls == 0 ? 0 : (double) totalRepeats / totalCalls;

        double avgSummarizer = all.stream()
                .mapToInt(RunResult::summarizerInvocations).average().orElse(0);

        // 业务上下文合规率: cursor 改写时检查 requirement 是否允许改 API. 由 EvalRunner.runSingle
        // 调 isBusinessContextCompliant 写入 RunResult.businessContextCompliant, 这里取 ratio.
        double businessRate = ratio(all, RunResult::businessContextCompliant);

        // 异常矩阵
        Map<String, Integer> failureMap = new HashMap<>();
        for (RunResult r : all) {
            if (r.toolFailuresByReason() != null) {
                r.toolFailuresByReason().forEach((reason, n) ->
                        failureMap.merge(reason, n, Integer::sum));
            }
        }

        // Case 级聚合
        List<CaseAggregate> caseDetails = resultsPerCase.entrySet().stream()
                .map(e -> aggregateCase(e.getKey(), e.getValue()))
                .toList();

        return new EvalReport(
                config.promptVersion(), Instant.now(),
                resultsPerCase.size(), totalRuns,
                p95, 0.0,
                outcomeMatch, rewritePrec, rewritePrecStrict, unsupRecall,
                verifyPass, verifyUndet, costMed,
                speedupMed, speedupMax,
                businessRate, assumptionsRate,
                avgRounds, maxRounds, repeatRate, avgSummarizer, failureMap,
                caseDetails, all);
    }

    private static CaseAggregate aggregateCase(String caseId, List<RunResult> runs) {
        int pass = (int) runs.stream().filter(RunResult::outcomeMatched).count();
        String mostCommon = runs.stream()
                .filter(r -> r.diagnosis() != null)
                .findFirst()
                .map(r -> r.diagnosis().outcome().name())
                .orElse("ERROR");
        return new CaseAggregate(
                caseId, pass, runs.size(),
                mostCommon,
                runs.stream().mapToLong(RunResult::latencyMs).average().orElse(0),
                runs.stream().mapToLong(RunResult::totalTokens).average().orElse(0),
                runs.stream().mapToInt(RunResult::reactRounds).average().orElse(0),
                runs.stream().filter(r -> r.error() != null)
                        .map(RunResult::error).toList());
    }

    private static EvalReport empty(EvalConfig config) {
        return new EvalReport(config.promptVersion(), Instant.now(),
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, Map.of(),
                List.of(), List.of());
    }

    private static double ratio(List<RunResult> all, java.util.function.Predicate<RunResult> p) {
        return all.isEmpty() ? 0 : (double) all.stream().filter(p).count() / all.size();
    }

    /** 在 bucket(满足 bucketFilter 的子集)上算 p 的命中率, bucket 为空时返回 0. */
    private static double ratioWhere(List<RunResult> all,
                                     java.util.function.Predicate<RunResult> bucketFilter,
                                     java.util.function.Predicate<RunResult> p) {
        long bucketSize = all.stream().filter(bucketFilter).count();
        if (bucketSize == 0) return 0;
        long matched = all.stream().filter(bucketFilter).filter(p).count();
        return (double) matched / bucketSize;
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) return 0;
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[mid - 1] + sorted[mid]) / 2.0
                : sorted[mid];
    }
}
