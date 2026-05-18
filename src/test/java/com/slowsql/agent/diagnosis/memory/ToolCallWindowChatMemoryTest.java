package com.slowsql.agent.diagnosis.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯滑窗 baseline 按工具调用数算的关键契约 — 跟 LayeredChatMemory 的 recent 区行为一致,
 * 但不做 archive / summarizer / fact store. parallel calling 场景下保留稳定容量.
 */
class ToolCallWindowChatMemoryTest {

    @Test
    void systemAndUserAreAlwaysReturnedFirst() {
        ToolCallWindowChatMemory mem = new ToolCallWindowChatMemory("t", 3);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void overflowCyclesAreDroppedNotArchived() {
        // K=2 → 最近 2 次工具调用对应的 cycles 保留, 早期直接丢 (跟 LayeredChatMemory 的 spill
        // 进 archive 形成对比 — 这才是 baseline 的核心特征).
        ToolCallWindowChatMemory mem = new ToolCallWindowChatMemory("t", 2);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        for (int i = 1; i <= 5; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain", "{\"status\":\"ok\"}"));
        }
        // 5 个 cycle, K=2 → recent 应只剩最后 2 个 (cycle 4, 5)
        // out = [SYS, USR, AI4, T4, AI5, T5] = 6 条
        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(6);
        long aiWithTools = out.stream()
                .filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .count();
        assertThat(aiWithTools).isEqualTo(2);
    }

    @Test
    void parallelToolCallsCountByToolResultNotByAiMessage() {
        // 关键回归点: 一个 AiMessage 含 3 个 toolReq + 跟随 3 个 ToolResult, 算 3 次工具调用.
        // K=2 → 这个单条 AiMessage 的 3 个 ToolResult 已经超额, 但因为切点对齐 AiMessage 边界,
        // 整条 AiMessage + 3 个 ToolResult 都被保留 (不能拆出孤儿 ToolResult).
        ToolCallWindowChatMemory mem = new ToolCallWindowChatMemory("t", 2);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        AiMessage parallel = AiMessage.from(
                ToolExecutionRequest.builder().id("p1").name("getTableInfo").arguments("{}").build(),
                ToolExecutionRequest.builder().id("p2").name("getTableInfo").arguments("{}").build(),
                ToolExecutionRequest.builder().id("p3").name("getTableInfo").arguments("{}").build());
        mem.add(parallel);
        mem.add(ToolExecutionResultMessage.from("p1", "getTableInfo", "{}"));
        mem.add(ToolExecutionResultMessage.from("p2", "getTableInfo", "{}"));
        mem.add(ToolExecutionResultMessage.from("p3", "getTableInfo", "{}"));

        // 3 个工具调用都在最后一条 AiMessage 之后 → 整个 cycle 保留
        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(6); // SYS + USR + parallel AI + 3 ToolResult
    }

    @Test
    void clearResetsState() {
        ToolCallWindowChatMemory mem = new ToolCallWindowChatMemory("t", 3);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));
        mem.add(aiToolCall("c1", "runExplain"));
        mem.add(ToolExecutionResultMessage.from("c1", "runExplain", "{}"));

        mem.clear();
        assertThat(mem.messages()).isEmpty();
    }

    @Test
    void rejectsNonPositiveK() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ToolCallWindowChatMemory("t", 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ToolCallWindowChatMemory("t", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiMessage aiToolCall(String id, String toolName) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id(id).name(toolName).arguments("{}").build());
    }
}
