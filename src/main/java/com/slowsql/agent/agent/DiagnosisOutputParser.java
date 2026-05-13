package com.slowsql.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 LLM 最终输出的 JSON 串 → DiagnosisResult.
 *
 * 容错策略:
 *   1. 优先解析整段为 JSON.
 *   2. 失败则提取第一个 {} 块再试.
 *   3. 仍失败则返回 DiagnosisResult.unsupported("output_parse_error", ...), 不抛异常,
 *      让评测层把这类失败计入 outcome_mismatch 而不是直接挂掉.
 */
public final class DiagnosisOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DiagnosisOutputParser() {}

    public static DiagnosisResult parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return DiagnosisResult.unsupported("empty_llm_output", List.of());
        }
        String json = extractJsonBlock(llmOutput);
        try {
            JsonNode root = MAPPER.readTree(json);
            return toResult(root);
        } catch (Exception e) {
            return DiagnosisResult.unsupported(
                    "output_parse_error: " + e.getClass().getSimpleName(),
                    List.of("LLM 输出非合法 JSON, 原始片段: "
                            + truncate(llmOutput, 200)));
        }
    }

    private static DiagnosisResult toResult(JsonNode root) {
        String outcomeStr = textOrEmpty(root, "outcome");
        OutcomeType outcome;
        try {
            outcome = OutcomeType.fromJsonValue(outcomeStr);
        } catch (Exception e) {
            return DiagnosisResult.unsupported(
                    "unknown_outcome: " + outcomeStr, List.of());
        }

        String rewritten = null;
        JsonNode rewrittenNode = root.get("rewritten_sql");
        if (rewrittenNode != null && !rewrittenNode.isNull() && !rewrittenNode.asText().isBlank()) {
            rewritten = rewrittenNode.asText();
        }

        List<String> assumptions = toStringList(root.get("assumptions"));
        double confidence = root.has("confidence") ? root.get("confidence").asDouble(0.0) : 0.0;
        List<String> suggestions = toStringList(root.get("additional_suggestions"));

        return new DiagnosisResult(outcome, rewritten, assumptions, confidence, suggestions);
    }

    private static String extractJsonBlock(String raw) {
        String trimmed = raw.trim();
        // 去掉常见的 ```json ... ``` 围栏
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private static String textOrEmpty(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private static List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && !item.isNull()) {
                out.add(item.asText());
            }
        });
        return List.copyOf(out);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
