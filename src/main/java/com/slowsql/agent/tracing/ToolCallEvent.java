package com.slowsql.agent.tracing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次 @Tool 方法调用的完整事件 — 记录 args / result / 耗时 / 失败原因.
 *
 * 关键字段:
 *   - argsJson: 工具入参的完整 JSON 序列化 (不截断), 包括 verify 的双 SQL.
 *     这是 dj_006 / dj_005 这种"verify 反复失败"追因的核心证据 — 一眼看出
 *     LLM 实际写的 4 个改写 SQL 长什么样.
 *   - resultJson: 工具返回的完整 JSON (不截断), 含 verify reason / first_diff_row_index /
 *     plan / sampled_rows 等. 失败时给追因, 成功时给"为什么 pass"的解释.
 *   - failed / failReason: 跟 AgentStatsListener.failuresByReason 一致, 但 per-event 粒度.
 *     比 stats 的"次数计数"信息密度高得多.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallEvent(
        long elapsedMs,
        String toolName,
        String argsJson,
        String resultJson,
        long durationMs,
        boolean failed,
        String failReason
) implements TraceEvent {

    @Override
    @JsonProperty("type")
    public String type() { return "tool_call"; }
}
