package com.slowsql.agent.diagnosis.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 不做摘要, 等价于"老周期直接丢弃". 单测默认走这个, 也作为 LLM 不可用时的兜底.
 */
public final class NoOpHistorySummarizer implements HistorySummarizer {

    @Override
    public String summarize(List<ChatMessage> toCompress, String existing) {
        return existing;
    }
}
