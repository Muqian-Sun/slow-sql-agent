package com.slowsql.agent.diagnosis.memory;

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
    void spillCountsByToolResultNotByAiMessage() {
        // 验证出滑窗按 tool call 数 (ToolExecutionResultMessage) 计, 不按 AiMessage 数.
        // K=3 + 一条 parallel AiMessage 含 3 个 toolReq + 1 条单 toolReq → 共 4 个 ToolResult.
        // recent 应保留最近 3 个 ToolResult (后 3 个), 切点对齐到 parallel AiMessage 边界.
        RecordingSummarizer summarizer = new RecordingSummarizer();
        LayeredChatMemory mem = new LayeredChatMemory(
                "t", /*keepRecentToolCalls*/ 3, /*tokenThreshold*/ 1_000_000,
                new KeyFactStore(), new FactExtractor(), summarizer);
        mem.add(SystemMessage.from("SYS"));
        mem.add(UserMessage.from("USR"));

        // cycle A: 一个 AiMessage 含 3 个 toolReq → 3 个 ToolResult 跟随 (parallel)
        AiMessage parallel = AiMessage.from(
                ToolExecutionRequest.builder().id("p1").name("getTableInfo").arguments("{}").build(),
                ToolExecutionRequest.builder().id("p2").name("getTableInfo").arguments("{}").build(),
                ToolExecutionRequest.builder().id("p3").name("getTableInfo").arguments("{}").build());
        mem.add(parallel);
        mem.add(ToolExecutionResultMessage.from("p1", "getTableInfo", "{}"));
        mem.add(ToolExecutionResultMessage.from("p2", "getTableInfo", "{}"));
        mem.add(ToolExecutionResultMessage.from("p3", "getTableInfo", "{}"));
        // cycle B: 普通单 toolReq + ToolResult
        mem.add(aiToolCall("c-B", "runExplain"));
        mem.add(ToolExecutionResultMessage.from("c-B", "runExplain", "{}"));

        // K=3 → 最近 3 个 ToolResult: cycle A 的 p2/p3 + cycle B → 切点 = cycle A 的 parallel AiMessage
        // recent 内容: [parallel-AI, p1-tr, p2-tr, p3-tr, B-AI, B-tr] = 6 条
        // archive 应空 (cycle A 起点就是 keep-from, 没东西可 spill)
        assertThat(mem.pendingSize()).isZero();
        // token 阈值远未到, 压缩不触发
        assertThat(summarizer.invocations).isEmpty();
    }

    @Test
    void compressionTriggersOnlyByTokenThreshold() {
        // 验证压缩唯一触发条件是 token 阈值, 与 tool call 数完全无关.
        // 即使堆很多 tool call, 只要 token 不超阈值就不调 summarizer.
        RecordingSummarizer cycleOnly = new RecordingSummarizer();
        LayeredChatMemory tokenSafe = new LayeredChatMemory(
                "c", /*K*/ 2, /*tokenThreshold*/ 1_000_000,
                new KeyFactStore(), new FactExtractor(), cycleOnly);
        tokenSafe.add(SystemMessage.from("S"));
        tokenSafe.add(UserMessage.from("U"));
        for (int i = 1; i <= 10; i++) {
            tokenSafe.add(aiToolCall("c-" + i, "runExplain"));
            tokenSafe.add(ToolExecutionResultMessage.from("c-" + i, "runExplain", "{}"));
        }
        // 10 个 tool call 堆积, archive 累积 ≥ 8 个 ToolResult, 但 token 远低于阈值 → 不触发
        assertThat(cycleOnly.invocations).isEmpty();
        assertThat(tokenSafe.summary()).isNull();

        // 反例: token 阈值低则触发, 跟 tool call 数无关
        RecordingSummarizer tokenTrig = new RecordingSummarizer();
        LayeredChatMemory tokenSmall = new LayeredChatMemory(
                "t", /*K*/ 1, /*tokenThreshold*/ 30,
                new KeyFactStore(), new FactExtractor(), tokenTrig);
        tokenSmall.add(SystemMessage.from("SYS_PAD_TEXT"));
        tokenSmall.add(UserMessage.from("USR_PAD_TEXT_LONGER"));
        for (int i = 1; i <= 2; i++) {
            tokenSmall.add(aiToolCall("c-" + i, "runExplain"));
            tokenSmall.add(ToolExecutionResultMessage.from("c-" + i, "runExplain",
                    "{\"long_payload\":\"x\"}"));
        }
        assertThat(tokenTrig.invocations).isNotEmpty();
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
