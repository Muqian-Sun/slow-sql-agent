package com.slowsql.agent.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmHistorySummarizerTest {

    @Test
    void renderedPromptContainsBothExistingAndNewMessages() {
        StubChatModel stub = new StubChatModel("既有摘要 + 新增的合并版本");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        List<ChatMessage> toCompress = List.of(
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("c1").name("getTableInfo").arguments("{\"table\":\"orders\"}")
                        .build()),
                ToolExecutionResultMessage.from("c1", "getTableInfo",
                        "{\"status\":\"ok\",\"table\":\"orders\"}")
        );
        String existing = "前一轮: 查了 users 表";

        String merged = summarizer.summarize(toCompress, existing);

        assertThat(merged).isEqualTo("既有摘要 + 新增的合并版本");
        assertThat(stub.callCount).isEqualTo(1);
        // 既有摘要进入 prompt
        assertThat(stub.lastPrompt).contains("前一轮: 查了 users 表");
        // 新增工具调用进入 prompt
        assertThat(stub.lastPrompt).contains("getTableInfo");
        assertThat(stub.lastPrompt).contains("orders");
        // 不出现 JSON / markdown 等输出格式要求
        assertThat(stub.lastPrompt).contains("不要 JSON");
    }

    @Test
    void emptyInputReturnsExistingUntouched() {
        StubChatModel stub = new StubChatModel("不应被调用");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        String result = summarizer.summarize(List.of(), "原摘要");

        assertThat(result).isEqualTo("原摘要");
        assertThat(stub.callCount).isZero();
    }

    @Test
    void modelExceptionPropagatesToCaller() {
        StubChatModel stub = new StubChatModel(null);
        stub.throwOnCall = true;
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        List<ChatMessage> toCompress = List.of(AiMessage.from("hi"));
        // LLM 失败向上透传, 由 LayeredChatMemory 决定降级策略 (写占位 / WARN).
        assertThatThrownBy(() -> summarizer.summarize(toCompress, "前摘要"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated llm failure");
    }

    @Test
    void blankResponseFallsBackToExisting() {
        StubChatModel stub = new StubChatModel("   ");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        String result = summarizer.summarize(List.of(AiMessage.from("hi")), "前摘要");

        assertThat(result).isEqualTo("前摘要");
    }

    @Test
    void invocationCountReflectsActualLlmCalls() {
        StubChatModel stub = new StubChatModel("OK");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        assertThat(summarizer.invocationCount()).isZero();

        // 非空输入触发一次
        summarizer.summarize(List.of(AiMessage.from("c1")), null);
        assertThat(summarizer.invocationCount()).isEqualTo(1);

        // 再触发一次, 计数累加
        summarizer.summarize(List.of(AiMessage.from("c2")), "前");
        assertThat(summarizer.invocationCount()).isEqualTo(2);

        // 空输入早退, 不计数
        summarizer.summarize(List.of(), "前");
        assertThat(summarizer.invocationCount()).isEqualTo(2);
    }

    @Test
    void existingNullRendersAsAbsentMarker() {
        StubChatModel stub = new StubChatModel("OK");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(stub);

        summarizer.summarize(List.of(AiMessage.from("first cycle")), null);

        assertThat(stub.lastPrompt).contains("(无)");
    }

    // ------------------------------------------------------------------

    private static class StubChatModel implements ChatModel {
        private final String response;
        String lastPrompt;
        int callCount;
        boolean throwOnCall;

        StubChatModel(String response) {
            this.response = response;
        }

        @Override
        public String chat(String prompt) {
            this.callCount++;
            this.lastPrompt = prompt;
            if (throwOnCall) throw new RuntimeException("simulated llm failure");
            return response;
        }
    }
}
