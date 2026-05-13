package com.slowsql.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 评测黄金集中的单条 case,镜像 samples/golden_set.json 结构.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenCase(
        String id,
        String complexity,
        List<String> tags,
        Integer productionFrequency,
        Input input,
        Expected expected,
        String notes
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(
            String sql,
            List<String> schemaRequired,
            String dataVolumeHint,
            String requirement                  // 业务说明自然语言, 可为 null
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expected(
            String expectedOutcome,              // rewritten_deferred_join / cursor / ...
            List<String> acceptableOutcomes,     // 多种合理 outcome,可选
            Double minCostReductionPercent,
            Boolean mustPassVerification,
            String notesForEvaluator
    ) {}
}
