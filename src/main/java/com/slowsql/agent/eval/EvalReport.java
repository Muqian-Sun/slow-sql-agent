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
        double highConfidenceRate,
        double tokenReductionVsBaseline,    // 与 baseline 对比的 token 降幅,需配 comparedTo

        // ─── 第 2 层:效果 ───
        double outcomeMatchRate,
        double verificationPassRate,
        double verificationUndeterminedRate,
        double costReductionMedian,
        double businessContextCompliance,
        double assumptionsExplicitRate,

        // ─── 第 3 层:行为 ───
        double avgReactRounds,
        int maxReactRounds,
        double repeatedToolCallRate,
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
        double highConf = ratio(all, RunResult::isHighConfidence);
        double assumptionsRate = ratio(all, RunResult::hasExplicitAssumptions);

        double[] sortedCost = all.stream().mapToDouble(RunResult::costReductionPercent).sorted().toArray();
        double costMed = median(sortedCost);

        int totalCalls = all.stream().mapToInt(RunResult::totalToolCalls).sum();
        int totalRepeats = all.stream().mapToInt(RunResult::repeatedToolCalls).sum();
        double repeatRate = totalCalls == 0 ? 0 : (double) totalRepeats / totalCalls;

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
                p95, highConf, 0.0,
                outcomeMatch, verifyPass, verifyUndet, costMed, businessRate, assumptionsRate,
                avgRounds, maxRounds, repeatRate, failureMap,
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
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, Map.of(),
                List.of(), List.of());
    }

    private static double ratio(List<RunResult> all, java.util.function.Predicate<RunResult> p) {
        return all.isEmpty() ? 0 : (double) all.stream().filter(p).count() / all.size();
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
