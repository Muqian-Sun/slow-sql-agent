package com.slowsql.agent.diagnosis.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 纯滑窗 ChatMemory, **按工具调用次数算窗口** — 跟 LayeredChatMemory.keepRecentToolCalls
 * 同维度对照, 但**不做 archive / summarizer / KeyFactStore 等增强**.
 *
 * 跟 LangChain4j 自带 MessageWindowChatMemory(maxMessages=N) 的关键区别:
 *   - MessageWindow 按消息数算容量, parallel tool calling 下一个 AiMessage 多个 toolReq
 *     会让"消息数"语义跟"上下文密度"脱节. dj_005/006 实测中 MessageWindow(10) 实际只装
 *     2-3 cycle, 而 layered keepRecentToolCalls=3 稳定保留 3 次工具调用对应的 cycle.
 *   - 本实现按 ToolExecutionResultMessage 计数, 复用 LayeredChatMemory.findKeepFromIndex
 *     的同一套切点对齐逻辑 (向前找最近 AiMessage(toolReq) 边界, 防孤儿 ToolResult).
 *
 * 设计目的: 给 MemoryComparison 做"等容量同维度"对照. 唯一变量是 layered 的 archive 累积
 * 压缩 + KeyFactStore 旁路, 而不是混"消息数 vs 工具调用数"两个不同维度让对比失真.
 *
 * 不是为生产 default 用 — 生产路径就是 LayeredChatMemory.
 */
public class ToolCallWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxRecentToolCalls;

    private SystemMessage originalSystem;
    private UserMessage userMessage;
    /** 最近 maxRecentToolCalls 次工具调用对应的 cycles, 原样保留, 没有 archive. */
    private final List<ChatMessage> recent = new ArrayList<>();

    public ToolCallWindowChatMemory(Object id, int maxRecentToolCalls) {
        this.id = Objects.requireNonNull(id);
        if (maxRecentToolCalls <= 0) {
            throw new IllegalArgumentException("maxRecentToolCalls must be > 0");
        }
        this.maxRecentToolCalls = maxRecentToolCalls;
    }

    @Override
    public Object id() { return id; }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message == null) return;
        if (message instanceof SystemMessage sm) {
            this.originalSystem = sm;
            return;
        }
        if (message instanceof UserMessage um) {
            this.userMessage = um;
            return;
        }
        recent.add(message);
        // 完全复用 LayeredChatMemory 的截断逻辑: ToolResult 数计 + AiMessage 边界对齐.
        // 区别只在"超出的 cycle 怎么处理": layered 进 archive, 这里直接丢.
        int keepFromIdx = LayeredChatMemory.findKeepFromIndex(recent, maxRecentToolCalls);
        if (keepFromIdx > 0) {
            recent.subList(0, keepFromIdx).clear();
        }
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>();
        if (originalSystem != null) out.add(originalSystem);
        if (userMessage != null) out.add(userMessage);
        out.addAll(recent);
        return out;
    }

    @Override
    public synchronized void clear() {
        originalSystem = null;
        userMessage = null;
        recent.clear();
    }

    public int maxRecentToolCalls() { return maxRecentToolCalls; }
}
