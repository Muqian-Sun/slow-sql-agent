package com.slowsql.agent.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 单 case 一次 run 的完整执行 trace, 落盘后可独立打开看完整时间线.
 *
 * 序列化形态 (JSON):
 * <pre>
 * {
 *   "caseId": "case_dp_dj_006",
 *   "iteration": 0,
 *   "startedAt": "2026-05-19T...",
 *   "totalDurationMs": 50686,
 *   "finalStatus": "failure",
 *   "errorMessage": "Tool call limit exceeded: verifyResultEquivalence > 15",
 *   "events": [
 *     {"type": "tool_call", "elapsedMs": 1240, "toolName": "getTableInfo",
 *      "argsJson": "{\"tableName\":\"orders\"}", "resultJson": "{...}",
 *      "durationMs": 380, "failed": false},
 *     {"type": "tool_call", "elapsedMs": 5210, "toolName": "verifyResultEquivalence",
 *      "argsJson": "{\"originalSql\":\"...\",\"rewrittenSql\":\"...\"}",
 *      "resultJson": "{\"status\":\"fail\",\"reason\":\"content_mismatch\",...}",
 *      "durationMs": 920, "failed": true, "failReason": "verify_fail"},
 *     ...
 *   ]
 * }
 * </pre>
 *
 * 用途:
 *   - 事后追因 dj_006 这种黑洞失败 case: 直接读 trace 看每次 LLM 写了什么 SQL、
 *     verify 返回什么 reason、first_diff_row 在哪.
 *   - 不重跑就能复盘, 节省 LLM 调用成本.
 */
public record RunTrace(
        String caseId,
        int iteration,
        String startedAt,            // ISO-8601, 不依赖 jsr310 模块
        long totalDurationMs,
        String finalStatus,          // "success" | "failure"
        String errorMessage,         // 失败时填异常 message, 成功时 null
        List<TraceEvent> events
) {

    public static RunTrace success(String caseId, int iteration, Instant startedAt,
                                   long totalDurationMs, List<TraceEvent> events) {
        return new RunTrace(caseId, iteration, startedAt.toString(),
                totalDurationMs, "success", null, events);
    }

    public static RunTrace failure(String caseId, int iteration, Instant startedAt,
                                   long totalDurationMs, String errorMessage,
                                   List<TraceEvent> events) {
        return new RunTrace(caseId, iteration, startedAt.toString(),
                totalDurationMs, "failure", errorMessage, events);
    }

    /**
     * 落盘到 outputDir/case_<id>_iter_<N>_<ts>.json. ts 取自 startedAt 替换 :/. 为 -.
     * 文件名设计让 ls -lt 后能按时间排序, 不撞名.
     */
    public Path writeJson(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String ts = startedAt.replaceAll("[:.]", "-");
        Path out = outputDir.resolve("case_" + caseId + "_iter_" + iteration + "_" + ts + ".json");
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(out, mapper.writeValueAsString(this));
        return out;
    }
}
