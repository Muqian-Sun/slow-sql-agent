package com.slowsql.agent.eval;

import java.nio.file.Path;
import java.util.List;

/**
 * 评测配置.
 *
 * 4 种典型场景:
 * - Smoke:5 条核心 case × 1 次,~1 分钟,CI PR 用
 * - Full:全量 20 条 × 3 次,~30 分钟,每周跑
 * - A/B:全量 × 3 次 × 2 个版本,~1 小时,prompt 改动决策用
 * - Targeted:指定 case × N 次,debug 用
 */
public record EvalConfig(
        Path goldenSetJson,
        List<String> caseIds,           // 空表示全量
        int iterations,                 // LLM 采样波动,取均值需 N=3+
        String promptVersion,
        String comparedTo,              // 可选,对比的 baseline 版本
        boolean generateHtmlReport,
        Path reportOutputDir
) {

    public static EvalConfig smoke(Path goldenSetJson, Path reportOutputDir) {
        return new EvalConfig(
                goldenSetJson,
                GoldenSetLoader.SMOKE_CASE_IDS,
                1,
                "v1", null, true, reportOutputDir);
    }

    public static EvalConfig full(Path goldenSetJson, Path reportOutputDir, String version) {
        return new EvalConfig(
                goldenSetJson, List.of(), 3, version, null, true, reportOutputDir);
    }

    public static EvalConfig targeted(Path goldenSetJson, Path reportOutputDir,
                                      List<String> caseIds, int iterations) {
        return new EvalConfig(
                goldenSetJson, caseIds, iterations, "v1", null, true, reportOutputDir);
    }
}
