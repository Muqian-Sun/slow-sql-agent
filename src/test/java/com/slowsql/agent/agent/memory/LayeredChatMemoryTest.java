package com.slowsql.agent.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredChatMemoryTest {

    @Test
    void emptyMemoryReturnsEmpty() {
        LayeredChatMemory mem = new LayeredChatMemory("t");
        assertThat(mem.messages()).isEmpty();
    }

    @Test
    void systemAndUserAreAlwaysReturnedFirst() {
        LayeredChatMemory mem = new LayeredChatMemory("t");
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) out.get(0)).text()).contains("SYS");
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void factsAreAppendedToSystemMessage() {
        LayeredChatMemory mem = new LayeredChatMemory("t");
        mem.add(SystemMessage.from("SYS_BASE"));
        mem.add(UserMessage.from("USR"));

        // 模拟 ToolExecutionResultMessage 触发 fact 抽取
        mem.add(aiToolCall("tool-call-1", "getTableInfo"));
        mem.add(ToolExecutionResultMessage.from("tool-call-1", "getTableInfo",
                "{\"status\":\"ok\",\"table\":\"orders\",\"indexes\":[{\"name\":\"PRIMARY\",\"unique\":true,\"columns\":[\"id\"]}],\"estimated_rows\":1000000}"));

        SystemMessage sys = (SystemMessage) mem.messages().get(0);
        assertThat(sys.text()).contains("SYS_BASE");
        assertThat(sys.text()).contains("已确认事实");
        assertThat(sys.text()).contains("table=orders");
    }

    @Test
    void cycleTruncationKeepsLastK() {
        LayeredChatMemory mem = new LayeredChatMemory("t", 2,
                new KeyFactStore(), new FactExtractor());
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        // 4 个 cycle, K=2 应该只保留最后 2 个
        for (int i = 1; i <= 4; i++) {
            mem.add(aiToolCall("call-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("call-" + i, "runExplain",
                    "{\"status\":\"ok\",\"rows\":[]}"));
        }
        // 最后一个 AiMessage 作为终止回答(无 tool call)
        mem.add(AiMessage.from("final answer"));

        List<ChatMessage> out = mem.messages();
        // [system, user, cycle3(2), cycle4(2), final(1)] = 7
        assertThat(out).hasSize(7);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);

        // 取出剩下的 AiMessage 工具调用 id
        long aiWithTools = out.stream()
                .filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .count();
        assertThat(aiWithTools).isEqualTo(2);
    }

    @Test
    void truncationKeepsAllWhenUnderCapacity() {
        LayeredChatMemory mem = new LayeredChatMemory("t", 3,
                new KeyFactStore(), new FactExtractor());
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));
        mem.add(aiToolCall("c1", "runExplain"));
        mem.add(ToolExecutionResultMessage.from("c1", "runExplain", "{}"));

        // 只 1 个 cycle, K=3, 全保留
        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(4);
    }

    @Test
    void clearResetsToEmpty() {
        LayeredChatMemory mem = new LayeredChatMemory("t");
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));
        mem.add(AiMessage.from("hi"));

        mem.clear();
        assertThat(mem.messages()).isEmpty();
    }

    @Test
    void trimHelperHandlesNoToolCycles() {
        // 全是 final answer, 没 tool cycle → 全保留
        List<ChatMessage> all = List.of(AiMessage.from("a"), AiMessage.from("b"));
        assertThat(LayeredChatMemory.trimToLastKCycles(all, 3)).hasSize(2);
    }

    private static AiMessage aiToolCall(String id, String toolName) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id(id)
                .name(toolName)
                .arguments("{}")
                .build());
    }
}
