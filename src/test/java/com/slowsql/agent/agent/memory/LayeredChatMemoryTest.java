package com.slowsql.agent.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void factsAreExtractedToStoreButNotInjectedIntoSystem() {
        // 新语义: facts 只入 KeyFactStore 不自动注入 system, 由 LLM 主动调 recallFacts 拉.
        LayeredChatMemory mem = new LayeredChatMemory("t");
        mem.add(SystemMessage.from("SYS_BASE"));
        mem.add(UserMessage.from("USR"));

        mem.add(aiToolCall("tool-call-1", "getTableInfo"));
        mem.add(ToolExecutionResultMessage.from("tool-call-1", "getTableInfo",
                "{\"status\":\"ok\",\"table\":\"orders\",\"indexes\":[{\"name\":\"PRIMARY\",\"unique\":true,\"columns\":[\"id\"]}],\"estimated_rows\":1000000}"));

        // system 保持原样, 不掺 facts
        SystemMessage sys = (SystemMessage) mem.messages().get(0);
        assertThat(sys.text()).isEqualTo("SYS_BASE");
        assertThat(sys.text()).doesNotContain("已确认事实");

        // facts 进入 KeyFactStore, 由 recallFacts 工具暴露
        assertThat(mem.factStore().size()).isEqualTo(1);
        assertThat(mem.factStore().render()).contains("table=orders");
    }

    @Test
    void overflowCyclesGoToPendingNotDroppedOnLowVolume() {
        // 新延迟压缩策略: 超 K 的老 cycle 进 pending 而非立即丢, 总 token 未到阈值时不调 LLM.
        // 这条 case 用默认阈值 + 极少消息 → 不触发压缩, pending 累积全部老 cycle.
        LayeredChatMemory mem = new LayeredChatMemory("t", 2,
                new KeyFactStore(), new FactExtractor());
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        for (int i = 1; i <= 4; i++) {
            mem.add(aiToolCall("call-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("call-" + i, "runExplain",
                    "{\"status\":\"ok\",\"rows\":[]}"));
        }
        mem.add(AiMessage.from("final answer"));

        // K=2 → 最近 2 cycle 在 recent, 前 2 cycle 进 pending. 默认阈值远未触发, 不压缩.
        // messages() 包含所有 cycle 的原样消息: [SYS, USR, AI1,T1,AI2,T2,AI3,T3,AI4,T4,AI_final] = 11
        List<ChatMessage> out = mem.messages();
        assertThat(out).hasSize(11);

        long aiWithTools = out.stream()
                .filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .count();
        assertThat(aiWithTools).isEqualTo(4); // 4 个 cycle 全在 prompt
        assertThat(mem.summary()).isNull();   // 未触发压缩
        assertThat(mem.pendingSize()).isGreaterThan(0); // pending 有内容
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

    @Test
    void summarizerNotInvokedBelowTokenThreshold() {
        // 超过 K=2 但 token 未达阈值 → 老 cycle 进 pending, summarizer 不调用
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory("t", 2,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        for (int i = 1; i <= 3; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"status\":\"ok\",\"rows\":[]}"));
        }

        assertThat(summarizer.invocations).isEmpty();
        assertThat(mem.summary()).isNull();
        assertThat(mem.pendingSize()).isGreaterThan(0);
    }

    @Test
    void summarizerInvokedWhenTokenThresholdReached() {
        // 用极小 token 阈值 (50) 模拟真触发压缩
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory("t", 2, 50,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS_BASE_LONG_TEXT_TO_INFLATE_TOKEN_COUNT"));
        mem.add(UserMessage.from("USR_LONG_TEXT_TO_INFLATE_TOKEN_COUNT_AS_WELL"));

        // K=2, 加 3 个 cycle → 第 1 cycle 进 pending → 总 token 估算超阈值 → 触发压缩
        for (int i = 1; i <= 3; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"status\":\"ok\",\"rows\":[]}_LONG_PAYLOAD_TO_INFLATE_TOKEN"));
        }

        assertThat(summarizer.invocations).isNotEmpty();
        assertThat(mem.summary()).contains("v1");
        assertThat(mem.pendingSize()).isZero(); // pending 已被 flush
    }

    @Test
    void summaryAppearsAsSystemMessageBetweenUserAndRecent() {
        // 小阈值 + K=1, 模拟 2 cycle 后总 token 超阈值, 第 1 cycle 进 pending 后被 flush 成 summary
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory("t", 1, 30,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS_PADDING_TEXT"));
        mem.add(UserMessage.from("USR_PADDING_TEXT_LONGER"));

        for (int i = 1; i <= 2; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"long_payload_inflating_token_count\":true}"));
        }

        List<ChatMessage> out = mem.messages();
        // [SYS, USR, SUMMARY(system), AI2, Tool2] = 5
        assertThat(out).hasSize(5);
        assertThat(out.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(out.get(1)).isInstanceOf(UserMessage.class);
        assertThat(out.get(2)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) out.get(2)).text())
                .contains("历史摘要").contains("v1");
    }

    @Test
    void summaryIsAbsentWhenNoCyclesCompressed() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory("t", 3,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));
        mem.add(aiToolCall("c1", "runExplain"));
        mem.add(ToolExecutionResultMessage.from("c1", "runExplain", "{}"));

        // 只 1 个 cycle, K=3, 不触发压缩
        assertThat(summarizer.invocations).isEmpty();
        assertThat(mem.summary()).isNull();
        // messages() 不含历史摘要的 system 行
        long systemCount = mem.messages().stream()
                .filter(m -> m instanceof SystemMessage)
                .count();
        assertThat(systemCount).isEqualTo(1);
    }

    @Test
    void summarizerExceptionWritesDegradedPlaceholderAndStillTrims() {
        // LLM 摘要失败时, pending 必须仍然清空防 OOM, summary 写降级占位让 LLM 知道有截断.
        // 小阈值确保触发压缩.
        HistorySummarizer failing = new HistorySummarizer() {
            @Override
            public String summarize(List<ChatMessage> toCompress, String existing) {
                throw new RuntimeException("llm down");
            }
        };
        LayeredChatMemory mem = new LayeredChatMemory("t", 1, 30,
                new KeyFactStore(), new FactExtractor(), failing);
        mem.add(SystemMessage.from("SYS_PADDING_LONG_ENOUGH"));
        mem.add(UserMessage.from("USR_PADDING_LONG_ENOUGH"));

        for (int i = 1; i <= 2; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"long_payload_to_inflate_tokens\":true}"));
        }

        // pending 已被 flush(尽管失败) 防 OOM
        assertThat(mem.pendingSize()).isZero();

        // summary 包含降级占位 — 早期截断对 LLM 可见, 不是无声丢
        assertThat(mem.summary()).isNotNull();
        assertThat(mem.summary()).contains("截断");
    }

    @Test
    void summaryAccumulatesAcrossMultipleCompressions() {
        // 小阈值让每加一个新 cycle 就触发一次压缩
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory("t", 1, 30,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS_PADDING_LONG_ENOUGH"));
        mem.add(UserMessage.from("USR_PADDING_LONG_ENOUGH"));

        for (int i = 1; i <= 4; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"long_payload_to_inflate_tokens\":true}"));
        }

        // K=1 + 小阈值 → 每加一个新 cycle 就 spill 到 pending, 触发阈值就 flush 到 summary.
        // 4 cycle 总共会调多次摘要器(每次 spill 后立刻 flush, 因为阈值小)
        assertThat(summarizer.invocations.size()).isGreaterThanOrEqualTo(2);
        // 第 2 次及之后的调用应该带着上次的累积摘要
        assertThat(summarizer.invocations.get(1).existing).contains("v1");
        // 最终 summary 应该是最后一次 flush 的产出
        assertThat(mem.summary()).startsWith("v");
    }

    @Test
    void summarizerTriggersByCycleCountEvenWhenTokenThresholdNotReached() {
        // 高 token 阈值 (永远到不了) + 低 cycle 阈值 (3) → 测 cycle 数触发的常规路径,
        // 而不是 token 阈值兜底路径. 防"延迟压缩=永不压缩"回归.
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory(
                "t", 2, /*tokenThreshold*/ 1_000_000, /*compressAfterCycles*/ 3,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        // K=2, compressAfterCycles=3. 加 5 cycle 后: recent 保留最后 2, archive raw 应 ≥ 3 → 触发
        for (int i = 1; i <= 5; i++) {
            mem.add(aiToolCall("c-" + i, "runExplain"));
            mem.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"status\":\"ok\"}"));
        }

        assertThat(summarizer.invocations).isNotEmpty();
        assertThat(mem.summary()).startsWith("v");
        assertThat(mem.pendingSize()).isZero();
    }

    @Test
    void cycleAndTokenThresholdsAreIndependent() {
        // 一边到 cycle 触发 / 一边到 token 触发, 任一即压
        RecordingSummarizer s1 = new RecordingSummarizer();
        LayeredChatMemory cycleOnly = new LayeredChatMemory(
                "c", 2, 1_000_000, 3,
                new KeyFactStore(), new FactExtractor(), s1);
        cycleOnly.add(SystemMessage.from("S"));
        cycleOnly.add(UserMessage.from("U"));
        for (int i = 1; i <= 5; i++) {
            cycleOnly.add(aiToolCall("c-" + i, "runExplain"));
            cycleOnly.add(ToolExecutionResultMessage.from("c-" + i, "runExplain", "{}"));
        }
        assertThat(s1.invocations).isNotEmpty();

        RecordingSummarizer s2 = new RecordingSummarizer();
        LayeredChatMemory tokenOnly = new LayeredChatMemory(
                "t", 1, 30, /*compressAfterCycles*/ 1_000,
                new KeyFactStore(), new FactExtractor(), s2);
        tokenOnly.add(SystemMessage.from("SYS_PAD_TEXT"));
        tokenOnly.add(UserMessage.from("USR_PAD_TEXT_LONGER"));
        for (int i = 1; i <= 2; i++) {
            tokenOnly.add(aiToolCall("c-" + i, "runExplain"));
            tokenOnly.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"long_payload\":\"x\"}"));
        }
        assertThat(s2.invocations).isNotEmpty();
    }

    private static AiMessage aiToolCall(String id, String toolName) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id(id)
                .name(toolName)
                .arguments("{}")
                .build());
    }

    /** 测试用 summarizer: 记录每次调用, 返回 "v{n}" 形式的可断言摘要. */
    private static class RecordingSummarizer implements HistorySummarizer {
        final List<Invocation> invocations = new ArrayList<>();

        @Override
        public String summarize(List<ChatMessage> toCompress, String existing) {
            Invocation inv = new Invocation(toCompress.size(), existing);
            invocations.add(inv);
            return "v" + invocations.size();
        }

        record Invocation(int droppedCount, String existing) {}
    }
}
