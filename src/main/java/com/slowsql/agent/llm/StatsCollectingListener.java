package com.slowsql.agent.llm;

import com.slowsql.agent.eval.AgentStatsListener;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;

/**
 * 桥接 LangChain4j 的 ChatModelListener 到内部 AgentStatsListener.
 *
 * 每次 LLM 响应记一次 ReAct 轮次 + 累加 token 数.
 * 失败按 reason 落到 failuresByReason.
 */
public class StatsCollectingListener implements ChatModelListener {

    private final AgentStatsListener stats;

    public StatsCollectingListener(AgentStatsListener stats) {
        this.stats = stats;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // no-op
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        ChatResponseMetadata metadata = responseContext.chatResponse().metadata();
        long tokens = 0;
        TokenUsage usage = metadata.tokenUsage();
        if (usage != null && usage.totalTokenCount() != null) {
            tokens = usage.totalTokenCount();
        }
        stats.onLlmResponse(tokens);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Throwable err = errorContext.error();
        String reason = err == null ? "unknown" : err.getClass().getSimpleName();
        stats.onToolFailure("llm_" + reason);
    }
}
