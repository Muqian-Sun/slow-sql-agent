package com.slowsql.agent.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 LLM 把"已超出 keepCycles 窗口的旧 ReAct 周期"压成一段紧凑中文摘要,
 * 作为"历史摘要"层注入到 LayeredChatMemory.
 *
 * 与 KeyFactStore 的分工:
 *   - KeyFactStore 抽"结构化事实"(table=orders pk=id ...), 是机器可读的离散事实
 *   - HistorySummarizer 抽"行为/推理脉络"(我先做了 X, 然后发现 Y, 还没回答 Z), 是连贯叙事
 *   - 两者互补: 事实回答"是什么", 摘要回答"做过什么 / 下一步要做什么"
 */
public final class LlmHistorySummarizer implements HistorySummarizer {

    /** 单条工具 JSON 的预览上限, 防 prompt 自身爆炸. */
    private static final int PER_MESSAGE_PREVIEW = 400;
    /** 默认输出软上限(字符), 提示给 LLM. */
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 400;

    private final ChatModel model;
    private final int maxOutputChars;
    private final AtomicInteger invocations = new AtomicInteger();

    public LlmHistorySummarizer(ChatModel model) {
        this(model, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public LlmHistorySummarizer(ChatModel model, int maxOutputChars) {
        this.model = Objects.requireNonNull(model);
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public int invocationCount() {
        return invocations.get();
    }

    /**
     * LLM 异常向上透传, 由 LayeredChatMemory 在 add() 流程里写降级占位 + WARN.
     * 这样调用方能区分"NoOp 静默丢"和"LLM 摘要失败", 不会让一条 cycle 无声蒸发.
     */
    @Override
    public String summarize(List<ChatMessage> toCompress, String existing) {
        if (toCompress == null || toCompress.isEmpty()) return existing;
        invocations.incrementAndGet();
        String prompt = buildPrompt(toCompress, existing);
        String response = model.chat(prompt);
        if (response == null) return existing;
        String trimmed = response.trim();
        return trimmed.isEmpty() ? existing : trimmed;
    }

    // ------------------------------------------------------------------

    private String buildPrompt(List<ChatMessage> toCompress, String existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ReAct 历史压缩器, 把下列 LLM 工具调用历史压缩成 ≤ ")
                .append(maxOutputChars).append(" 字的紧凑中文摘要.\n");
        sb.append("摘要需保留三件事:\n");
        sb.append("  1. 已采取的关键工具调用(name + 目的, 不要原样复制 JSON)\n");
        sb.append("  2. 已得到的关键事实(表 / 索引 / EXPLAIN / verify 结果, 简短结论)\n");
        sb.append("  3. 仍未解决或待验证的点\n");
        sb.append("输出仅一段紧凑文本, 不要 JSON / markdown / 解释性前后缀.\n\n");

        sb.append("[既有摘要]\n");
        sb.append(existing == null || existing.isBlank() ? "(无)" : existing.trim());
        sb.append("\n\n[新增待压缩消息]\n");
        sb.append(renderMessages(toCompress));
        return sb.toString();
    }

    private static String renderMessages(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            if (m instanceof AiMessage ai) {
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        sb.append("AI -> call ").append(req.name())
                                .append("(args=").append(preview(req.arguments())).append(")\n");
                    }
                    String text = ai.text();
                    if (text != null && !text.isBlank()) {
                        sb.append("AI think: ").append(preview(text)).append('\n');
                    }
                } else {
                    sb.append("AI: ").append(preview(ai.text())).append('\n');
                }
            } else if (m instanceof ToolExecutionResultMessage tm) {
                sb.append("TOOL(").append(tm.toolName()).append("): ")
                        .append(preview(tm.text())).append('\n');
            }
            // system / user 不应进入压缩窗口, 静默跳过
        }
        return sb.toString();
    }

    private static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= PER_MESSAGE_PREVIEW
                ? t
                : t.substring(0, PER_MESSAGE_PREVIEW) + "…";
    }
}
