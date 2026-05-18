package com.slowsql.agent.eval;

import com.slowsql.agent.diagnosis.api.DiagnosisResult;
import com.slowsql.agent.diagnosis.api.OutcomeType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 rewrite_precision_strict 与 rewrite_precision (loose) 的关系:
 *   - loose:  outcomeMatched 由 acceptable_outcomes 兜底
 *   - strict: 只看 outcome == expected_outcome, acceptable 不参与
 * delta = loose - strict 反映"保守标 unsupported 蒙对"的样本占比.
 */
class RewritePrecisionStrictTest {

    @Test
    void llmConservativeUnsupportedInflatesLooseButNotStrict() {
        // 5 个 case 都期望 deferred_join, acceptable 含 unsupported.
        // LLM 行为:
        //   3 个真的写出 deferred_join (strict + loose 都 match)
        //   2 个保守标 unsupported (loose match — 在 acceptable 集; strict 不 match)
        Map<String, List<RunResult>> results = new LinkedHashMap<>();
        results.put("case_A", List.of(rewriteCase("case_A", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));
        results.put("case_B", List.of(rewriteCase("case_B", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));
        results.put("case_C", List.of(rewriteCase("case_C", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));
        results.put("case_D", List.of(rewriteCase("case_D", OutcomeType.UNSUPPORTED, true)));   // acceptable 兜底
        results.put("case_E", List.of(rewriteCase("case_E", OutcomeType.UNSUPPORTED, true)));

        EvalConfig cfg = EvalConfig.smoke(Path.of("/tmp/x"), Path.of("/tmp/x"));
        EvalReport report = EvalReport.aggregate(results, cfg);

        // loose: 5/5 都算 match (因为 unsupported 在 acceptable 集)
        assertThat(report.rewritePrecision()).isEqualTo(1.0);
        // strict: 只 3/5 真写了 deferred_join
        assertThat(report.rewritePrecisionStrict()).isEqualTo(0.6);
        // delta = 40pp 暴露了 2 个 case 走"保守路径蒙对"
        double delta = report.rewritePrecision() - report.rewritePrecisionStrict();
        assertThat(delta).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void strictEqualsLooseWhenAllRewriteCasesActuallyRewrote() {
        // 全部都真的写了 expected outcome, 两个指标应该一致.
        Map<String, List<RunResult>> results = new LinkedHashMap<>();
        results.put("case_A", List.of(rewriteCase("case_A", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));
        results.put("case_B", List.of(rewriteCase("case_B", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));

        EvalConfig cfg = EvalConfig.smoke(Path.of("/tmp/x"), Path.of("/tmp/x"));
        EvalReport report = EvalReport.aggregate(results, cfg);

        assertThat(report.rewritePrecision()).isEqualTo(1.0);
        assertThat(report.rewritePrecisionStrict()).isEqualTo(1.0);
    }

    @Test
    void unsupportedCasesDoNotAffectRewritePrecision() {
        // 期望 unsupported 的 case 不进 rewrite_precision 分子分母 (分桶切开)
        Map<String, List<RunResult>> results = new LinkedHashMap<>();
        results.put("case_A", List.of(rewriteCase("case_A", OutcomeType.REWRITTEN_DEFERRED_JOIN, true)));
        results.put("case_X", List.of(unsupportedCase("case_X", OutcomeType.UNSUPPORTED, true)));

        EvalConfig cfg = EvalConfig.smoke(Path.of("/tmp/x"), Path.of("/tmp/x"));
        EvalReport report = EvalReport.aggregate(results, cfg);

        // rewrite_precision 只看 case_A (1/1=100%), strict 也 100%
        assertThat(report.rewritePrecision()).isEqualTo(1.0);
        assertThat(report.rewritePrecisionStrict()).isEqualTo(1.0);
        // unsupported_recall 只看 case_X (1/1=100%)
        assertThat(report.unsupportedRecall()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------

    /** expected=rewritten_deferred_join, acceptable 含 unsupported (典型 dj_006/dj_009 配置) */
    private static RunResult rewriteCase(String caseId, OutcomeType produced, boolean acceptableMatched) {
        DiagnosisResult diag = produced == OutcomeType.UNSUPPORTED
                ? DiagnosisResult.unsupported("test", List.of())
                : new DiagnosisResult(produced, "SELECT 1", List.of(), 0.9, List.of());
        return new RunResult(
                caseId, 0, diag, "rewritten_deferred_join",
                acceptableMatched, 0, false, "NOT_APPLICABLE", true, null,
                3, 0, 0, 0, Map.of(),
                1000L, 100L, null);
    }

    private static RunResult unsupportedCase(String caseId, OutcomeType produced, boolean matched) {
        DiagnosisResult diag = produced == OutcomeType.UNSUPPORTED
                ? DiagnosisResult.unsupported("test", List.of())
                : new DiagnosisResult(produced, "SELECT 1", List.of(), 0.9, List.of());
        return new RunResult(
                caseId, 0, diag, "unsupported",
                matched, 0, false, "NOT_APPLICABLE", true, null,
                2, 0, 0, 0, Map.of(),
                500L, 50L, null);
    }
}
