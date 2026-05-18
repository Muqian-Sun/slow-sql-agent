package com.slowsql.agent.tracing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 体系基本契约: 收集事件 → 落 JSON → 可机器读回.
 */
class TraceCollectorTest {

    @Test
    void noOpCollectorIgnoresEvents() {
        TraceCollector c = TraceCollector.noOp();
        c.record(new ToolCallEvent(0, "getTableInfo", "{}", "{}", 10, false, null));
        assertThat(c.snapshot()).isEmpty();
        assertThat(c.isNoOp()).isTrue();
    }

    @Test
    void realCollectorAccumulatesEvents() {
        TraceCollector c = new TraceCollector();
        c.record(new ToolCallEvent(100, "getTableInfo", "{\"tableName\":\"orders\"}",
                "{\"status\":\"ok\"}", 50, false, null));
        c.record(new ToolCallEvent(300, "verifyResultEquivalence", "{\"...\":\"...\"}",
                "{\"status\":\"fail\"}", 200, true, "verify_fail:content_mismatch"));

        var events = c.snapshot();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ToolCallEvent.class);
        ToolCallEvent first = (ToolCallEvent) events.get(0);
        assertThat(first.toolName()).isEqualTo("getTableInfo");
        assertThat(first.failed()).isFalse();
        ToolCallEvent second = (ToolCallEvent) events.get(1);
        assertThat(second.failed()).isTrue();
        assertThat(second.failReason()).contains("verify_fail");
    }

    @Test
    void runTraceWritesJsonWithEventsInOrder() throws Exception {
        TraceCollector c = new TraceCollector();
        c.record(new ToolCallEvent(50, "getTableInfo", "{\"tableName\":\"users\"}",
                "{\"status\":\"ok\",\"table\":\"users\"}", 30, false, null));
        c.record(new ToolCallEvent(200, "runExplain", "{\"sql\":\"SELECT 1\"}",
                "{\"status\":\"ok\",\"rows\":[]}", 100, false, null));

        Instant started = Instant.parse("2026-05-19T01:00:00Z");
        RunTrace trace = RunTrace.success("case_X", 0, started, 500, c.snapshot());

        Path tmpDir = Files.createTempDirectory("trace-test");
        Path out = trace.writeJson(tmpDir);
        assertThat(out).exists();
        String json = Files.readString(out);
        assertThat(json).contains("\"caseId\" : \"case_X\"");
        assertThat(json).contains("\"finalStatus\" : \"success\"");
        assertThat(json).contains("\"type\" : \"tool_call\"");
        assertThat(json).contains("getTableInfo");
        assertThat(json).contains("runExplain");
        // 事件顺序保留
        assertThat(json.indexOf("getTableInfo")).isLessThan(json.indexOf("runExplain"));
    }

    @Test
    void runTraceFailureCaptureErrorMessage() throws Exception {
        TraceCollector c = new TraceCollector();
        c.record(new ToolCallEvent(0, "verifyResultEquivalence", "{...}", "{\"status\":\"fail\"}",
                200, true, "verify_fail"));

        Instant started = Instant.parse("2026-05-19T01:00:00Z");
        RunTrace trace = RunTrace.failure("case_dj_006", 1, started, 50000L,
                "Tool call limit exceeded: verifyResultEquivalence > 15",
                c.snapshot());

        Path tmpDir = Files.createTempDirectory("trace-test");
        Path out = trace.writeJson(tmpDir);
        String json = Files.readString(out);
        assertThat(json).contains("\"finalStatus\" : \"failure\"");
        assertThat(json).contains("Tool call limit exceeded");
        assertThat(out.getFileName().toString()).contains("case_dj_006_iter_1");
    }
}
