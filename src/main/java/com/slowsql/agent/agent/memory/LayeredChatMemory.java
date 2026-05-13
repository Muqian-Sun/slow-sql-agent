package com.slowsql.agent.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 三段式 ChatMemory:
 *   [system + KeyFactStore.render()]  ← 每次 messages() 重组, 注入累积事实
 *   [user]                            ← 永远保留(原始 SQL + requirement)
 *   [last K ReAct cycles]             ← 旧的整周期丢掉, 防 token 线性增长
 *
 * 关键设计:
 *   - 截断单位是"周期"而不是"消息": 一个周期 = AiMessage(带 tool requests) + 后续 ToolExecutionResultMessage.
 *     单条消息截断会让 LLM 看到孤儿 tool request 或没匹配的 result, 模型会崩.
 *   - 接收 ToolExecutionResultMessage 时, 顺便喂给 FactExtractor 累积关键事实.
 *   - 不持久化, 每个 diagnose() 用新实例 — LangChain4jDiagnosisAgent 已经按 case 重建.
 *
 * 与 LangChain4j 默认 MessageWindowChatMemory 的区别:
 *   - 默认实现按消息数 truncate, 不感知 cycle 边界, 也不抽事实.
 *   - 默认 system message 是 immutable 第一条; 我们每轮重组 system, 把"已确认事实"块拼到末尾.
 */
public class LayeredChatMemory implements ChatMemory {

    /** 默认保留最近 K 个 ReAct 周期. 经验值: 3 个周期足够 LLM 看清最近上下文, 又不让 token 暴涨. */
    public static final int DEFAULT_KEEP_CYCLES = 3;

    private final Object id;
    private final int keepCycles;
    private final KeyFactStore factStore;
    private final FactExtractor extractor;

    private SystemMessage originalSystem;
    private UserMessage userMessage;
    /** 不含 system / user, 只含 ai/tool 消息. */
    private final List<ChatMessage> recent = new ArrayList<>();

    public LayeredChatMemory(Object id, int keepCycles,
                             KeyFactStore factStore, FactExtractor extractor) {
        this.id = Objects.requireNonNull(id);
        this.keepCycles = keepCycles;
        this.factStore = Objects.requireNonNull(factStore);
        this.extractor = Objects.requireNonNull(extractor);
    }

    public LayeredChatMemory(Object id) {
        this(id, DEFAULT_KEEP_CYCLES, new KeyFactStore(), new FactExtractor());
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message == null) return;
        if (message instanceof SystemMessage sm) {
            // AiServices 通常只设一次. 后续覆盖也无害(语义上是更新 system 提示).
            this.originalSystem = sm;
            return;
        }
        if (message instanceof UserMessage um) {
            this.userMessage = um;
            return;
        }
        if (message instanceof ToolExecutionResultMessage tm) {
            extractor.extract(tm.toolName(), tm.text(), factStore);
        }
        recent.add(message);
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>();
        if (originalSystem != null) {
            out.add(composeSystemMessage(originalSystem, factStore));
        }
        if (userMessage != null) {
            out.add(userMessage);
        }
        out.addAll(trimToLastKCycles(recent, keepCycles));
        return out;
    }

    @Override
    public synchronized void clear() {
        originalSystem = null;
        userMessage = null;
        recent.clear();
        // factStore 跟随 memory 生命周期, 也清.
        // 通过新建实例替换太重, 简单逐个清: 但 KeyFactStore 没暴露 clear, 工厂里给个空 list.
        // 这里简化: 不清 store, 由调用方控制(实际 agent 每 case 新建 memory + store).
    }

    public KeyFactStore factStore() { return factStore; }

    // ------------------------------------------------------------------

    /**
     * 重组 system message: 原系统提示 + KeyFactStore.render() 块.
     * factStore 为空时直接返回原 system, 不空就拼一块"已确认事实".
     */
    private static SystemMessage composeSystemMessage(SystemMessage original, KeyFactStore facts) {
        String factsText = facts.render();
        if (factsText.isEmpty()) return original;
        return SystemMessage.from(original.text() + "\n\n" + factsText);
    }

    /**
     * 从后往前找第 K 个"带 tool requests 的 AiMessage", 把那之前的消息全部丢掉.
     * 这等价于"保留最近 K 个 ReAct 周期"(周期 = AiMessage带工具 + 后续 ToolExecutionResultMessage).
     */
    static List<ChatMessage> trimToLastKCycles(List<ChatMessage> all, int k) {
        if (k <= 0 || all.isEmpty()) return new ArrayList<>(all);
        int cyclesSeen = 0;
        int keepFromIdx = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessage m = all.get(i);
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                cyclesSeen++;
                if (cyclesSeen >= k) {
                    keepFromIdx = i;
                    break;
                }
            }
        }
        return new ArrayList<>(all.subList(keepFromIdx, all.size()));
    }
}
