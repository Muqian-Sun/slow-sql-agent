package com.slowsql.agent.diagnosis.agent;

import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import com.slowsql.agent.diagnosis.tools.DiagnosisTools;
import com.slowsql.agent.dbinspect.MockToolBackend;
import com.slowsql.agent.diagnosis.tools.ToolCallLimitExceededException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 LangChain4j 1.12.1 的 toolExecutionErrorHandler 抛 RuntimeException 真的会冒泡出
 * AiServices.diagnose() — 而不是被框架 catch 后 wrap 成结果再喂回 LLM.
 *
 * 这是工具调用次数硬上限设计的关键 framework 假设, 字节码层面已确认; 本 IT 防版本漂移.
 *
 * 实现方式:
 *   - Stub ChatModel: 不调用真实 LLM, 每次返回同一个 verifyResultEquivalence 工具请求,
 *     模拟"LLM 死循环调 verify"
 *   - 期望 DiagnosisTools 第 LIMIT_VERIFY+1 次调用抛 ToolCallLimitExceededException,
 *     经 toolExecutionErrorHandler 重抛后冒泡出 advisor.diagnose()
 *   - 纯本地, 不依赖 MySQL / 任何 LLM endpoint
 */
class LangChain4jToolLimitIT {

    @Test
    void toolCallLimitExceptionPropagatesOutOfAiServices() {
        LoopingVerifyStub stub = new LoopingVerifyStub();
        DiagnosisTools tools = new DiagnosisTools(
                new MockToolBackend(), new AgentStatsListener(), new KeyFactStore());

        DeepPaginationAdvisor advisor = AiServices.builder(DeepPaginationAdvisor.class)
                .chatModel(stub)
                .tools(tools)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .maxSequentialToolsInvocations(30)
                .toolExecutionErrorHandler((throwable, ctx) -> {
                    // 与生产装配 (LangChain4jDiagnosisAgent#L102-116) 严格一致: 上限异常重抛,
                    // 其它 wrap 给 LLM. handler 抛出后 framework 必须让它冒泡, 否则硬熔断失效.
                    if (throwable instanceof ToolCallLimitExceededException tle) {
                        throw tle;
                    }
                    return ToolErrorHandlerResult.text(
                            throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                })
                .build();

        assertThatThrownBy(() -> advisor.diagnose("any user input"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .satisfies(e -> {
                    ToolCallLimitExceededException tle = (ToolCallLimitExceededException) e;
                    assertThat(tle.toolName()).isEqualTo("verifyResultEquivalence");
                    assertThat(tle.limit()).isEqualTo(DiagnosisTools.LIMIT_VERIFY);
                });

        // 控制 stub 至少被调用过 LIMIT_VERIFY+1 次 — 证明 framework 经过了多轮 tool loop,
        // 不是在第一次就被 catch 在别处.
        assertThat(stub.callCount()).isGreaterThan(DiagnosisTools.LIMIT_VERIFY);
    }

    /**
     * Stub ChatModel: 每次 doChat 都让 LLM "决定调 verify". 用以确定性地触发工具上限.
     * 不调任何远程服务, 也不解析 ChatRequest 内容.
     */
    private static class LoopingVerifyStub implements ChatModel {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            int n = counter.incrementAndGet();
            AiMessage ai = AiMessage.from(ToolExecutionRequest.builder()
                    .id("call-" + n)
                    .name("verifyResultEquivalence")
                    .arguments("{\"originalSql\":\"SELECT 1\",\"rewrittenSql\":\"SELECT 1\"}")
                    .build());
            return ChatResponse.builder().aiMessage(ai).build();
        }

        int callCount() { return counter.get(); }
    }
}
