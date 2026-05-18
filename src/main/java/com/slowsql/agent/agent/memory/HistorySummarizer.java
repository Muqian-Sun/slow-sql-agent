package com.slowsql.agent.agent.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 把"已超出 keepCycles 窗口的旧 ReAct 周期"压缩成一段紧凑摘要,
 * 由 LayeredChatMemory 在 add() 触发, 用于"历史摘要"层.
 *
 * 实现:
 *   - {@link NoOpHistorySummarizer} 直接丢弃旧消息(单测 / 不接 LLM 时用)
 *   - {@link LlmHistorySummarizer} 调 ChatModel 做语义压缩
 */
public interface HistorySummarizer {

    /**
     * @param toCompress 即将从 inline 窗口丢弃的消息(按对话顺序)
     * @param existing   既有累积摘要, 可能为 null/空
     * @return 压缩后的新摘要; 返回 null 表示不更新(等价于直接丢弃)
     */
    String summarize(List<ChatMessage> toCompress, String existing);

    /**
     * 本实例累计被调用次数(不含 toCompress 为空的早退). 仅用于评测可观测性,
     * NoOp 实现固定返回 0, 真正做 LLM 压缩的实现才有非零值.
     */
    default int invocationCount() {
        return 0;
    }
}
