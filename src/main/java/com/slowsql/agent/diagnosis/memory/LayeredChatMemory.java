package com.slowsql.agent.diagnosis.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 四层上下文 ChatMemory + 延迟压缩策略.
 *
 * 物理布局(prompt 拼接顺序):
 *   [1] originalSystem  不可压缩指令      永久, 不动
 *   [2] userMessage     不可压缩诉求      永久, 不动
 *   [3] archive         摘要区             单一 List, 0 或 1 条已压缩 SystemMessage 在头部, 后跟未压缩原样老 cycle
 *   [4] recent          当前轮             最近 keepCycles 个完整 ReAct 周期, 原样
 *
 *   旁路: KeyFactStore — 结构化事实库, 由 FactExtractor 在 ToolExecutionResultMessage
 *                        进入时旁路抽取; LLM 通过 recallFacts 工具按需 pull, 不强占 prompt.
 *
 * archive 的两态混合:
 *   - 刚 spill 进来的老 cycle 是原样消息(token 大但语义完整)
 *   - 触发阈值时压缩, 整个 archive(含**旧摘要**) 送 LLM 重新压缩成一条新 SystemMessage
 *     **关键: 每次压缩都把旧摘要一起送进去**, 让 LLM 把新增 raw 内容融进旧摘要,
 *     避免多次压缩产出多段独立摘要导致信息割裂.
 *   - 压缩后 archive 只剩一条已压缩 SystemMessage; 下次再 spill 时新 raw 又叠在它后面
 *
 * **关键边界: 压缩只发生在 archive, recent (当前轮) 永不压缩**.
 *   recent 是 LLM 最近 K 轮的原始上下文, 必须保持高保真度供 LLM 看清最近做了什么.
 *
 * 延迟压缩策略:
 *   1. cycle 数超 K → 老 cycle 从 recent **挪到 archive 末尾**(无 LLM 调用, 原样保留)
 *   2. 持续累积, 直到触发任一条件:
 *        a. 估算总 token 超过 tokenThreshold (高保真兜底, 防 prompt 爆窗口)
 *        b. archive 中累计 tool call 次数 ≥ compressAfterToolCalls (常规路径).
 *           粒度按 ToolExecutionResultMessage 计数, 而不是按 AiMessage —
 *           LLM 启用 parallel tool calls 时一个 AiMessage 可含多个 toolReq /
 *           对应多个 ToolResult, 按 AiMessage 数算会让复杂 case 永远到不了阈值.
 *           工具调用次数与"上下文真正堆了多少"线性相关, 是更稳的触发信号.
 *   3. 任一触发 → 整个 archive(旧摘要 + 新 raw) 一起送 LLM 重压, 产出新摘要替换全部
 *
 * 收益:
 *   - 简单 case (≤ keepCycles 轮 ReAct) 永远不进 archive → 0 额外 LLM 成本
 *   - 中等 case 在 compressAfterToolCalls 触发, 把早期 ReAct 周期压成一条摘要, 控制 prompt 增速
 *   - 长尾死循环 / 巨型 case 由 tokenThreshold 兜底防爆窗口
 *   - 旧摘要不会"独立堆积"; 始终保持 archive 头部最多一条摘要 + 后续新 raw 的形态
 *
 * cycle 截断单位:
 *   必须按"周期"(AiMessage + 后续 ToolExecutionResultMessage)粒度,
 *   按消息粒度会让 LLM 看到孤儿 tool request, OpenAI 兼容 API 直接报错.
 */
public class LayeredChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(LayeredChatMemory.class);

    /** 默认保留最近 K 个 ReAct 周期原样在 prompt 里. 经验值: 3 足够 LLM 看清最近脉络. */
    public static final int DEFAULT_KEEP_CYCLES = 3;

    /**
     * 默认 token 阈值. 估算总 token 超过此值才触发 archive 的 LLM 压缩.
     * 22400 ≈ 32k context 上限的 70%, 给改写 SQL 字符串 + 工具响应留 30% 余量.
     */
    public static final int DEFAULT_TOKEN_THRESHOLD = 22_400;

    /**
     * 默认 tool call 触发阈值. archive 中累计 ToolExecutionResultMessage 数 ≥ 此值即触发压缩.
     * 取 6: dj_005 / dj_006 实测 10-12 tool_calls (parallel 比例 ~1.5-1.7), keepCycles=3
     * 在 recent 占 3-5 tool calls, archive 堆到 ≥ 6 即触发. 简单 case (≤ 5 tool_calls 总数)
     * 不触发, 避免额外 LLM 成本.
     */
    public static final int DEFAULT_COMPRESS_AFTER_TOOL_CALLS = 6;

    /** 已压缩摘要 SystemMessage 的内容前缀, 用作"这条是已压缩摘要"的标记. */
    private static final String SUMMARY_PREFIX = "=== 历史摘要(早期 ReAct 周期, 已 LLM 语义压缩) ===\n";

    private final Object id;
    private final int keepCycles;
    private final int tokenThreshold;
    private final int compressAfterToolCalls;
    private final KeyFactStore factStore;
    private final FactExtractor extractor;
    private final HistorySummarizer summarizer;

    private SystemMessage originalSystem;
    private UserMessage userMessage;

    /**
     * 摘要区: 头部 0 或 1 条已压缩 SystemMessage(带 SUMMARY_PREFIX 标记) + 后续未压缩原样 cycle 消息.
     * 触发阈值时整段送 LLM 重压, 产出新 SystemMessage 替换整个 archive.
     */
    private final List<ChatMessage> archive = new ArrayList<>();

    /** 当前轮: 最近 keepCycles 个完整 ReAct 周期, 原样保留, 永不压缩. */
    private final List<ChatMessage> recent = new ArrayList<>();

    public LayeredChatMemory(Object id, int keepCycles, int tokenThreshold, int compressAfterToolCalls,
                             KeyFactStore factStore, FactExtractor extractor,
                             HistorySummarizer summarizer) {
        this.id = Objects.requireNonNull(id);
        this.keepCycles = keepCycles;
        this.tokenThreshold = tokenThreshold;
        this.compressAfterToolCalls = compressAfterToolCalls;
        this.factStore = Objects.requireNonNull(factStore);
        this.extractor = Objects.requireNonNull(extractor);
        this.summarizer = Objects.requireNonNull(summarizer);
    }

    public LayeredChatMemory(Object id, int keepCycles, int tokenThreshold,
                             KeyFactStore factStore, FactExtractor extractor,
                             HistorySummarizer summarizer) {
        this(id, keepCycles, tokenThreshold, DEFAULT_COMPRESS_AFTER_TOOL_CALLS,
                factStore, extractor, summarizer);
    }

    public LayeredChatMemory(Object id, int keepCycles,
                             KeyFactStore factStore, FactExtractor extractor,
                             HistorySummarizer summarizer) {
        this(id, keepCycles, DEFAULT_TOKEN_THRESHOLD, DEFAULT_COMPRESS_AFTER_TOOL_CALLS,
                factStore, extractor, summarizer);
    }

    public LayeredChatMemory(Object id, int keepCycles,
                             KeyFactStore factStore, FactExtractor extractor) {
        this(id, keepCycles, DEFAULT_TOKEN_THRESHOLD, DEFAULT_COMPRESS_AFTER_TOOL_CALLS,
                factStore, extractor, new NoOpHistorySummarizer());
    }

    public LayeredChatMemory(Object id) {
        this(id, DEFAULT_KEEP_CYCLES, DEFAULT_TOKEN_THRESHOLD, DEFAULT_COMPRESS_AFTER_TOOL_CALLS,
                new KeyFactStore(), new FactExtractor(), new NoOpHistorySummarizer());
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
        if (message instanceof ToolExecutionResultMessage tm) {
            extractor.extract(tm.toolName(), tm.text(), factStore);
        }
        recent.add(message);
        spillOverflowToArchive();
        if (hasRawInArchive()
                && (estimatedTokens() > tokenThreshold
                        || toolCallsInArchive() >= compressAfterToolCalls)) {
            compressArchive();
        }
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>();
        if (originalSystem != null) out.add(originalSystem);
        if (userMessage != null) out.add(userMessage);
        out.addAll(archive);   // 已压缩 SystemMessage (如有) + 未压缩 raw cycles 都在这
        out.addAll(recent);
        return out;
    }

    @Override
    public synchronized void clear() {
        originalSystem = null;
        userMessage = null;
        archive.clear();
        recent.clear();
    }

    public KeyFactStore factStore() { return factStore; }

    /** 当前累积的历史摘要文本(从 archive 头部提取), 无则 null. 仅用于观察 / 测试. */
    public synchronized String summary() {
        if (existingSummaryCount() == 1) {
            String text = ((SystemMessage) archive.get(0)).text();
            return text.substring(SUMMARY_PREFIX.length());
        }
        return null;
    }

    /** 历史摘要器被实际调用的次数(只统计真触发了 LLM 压缩的). */
    public int summarizerInvocations() { return summarizer.invocationCount(); }

    /** archive 中尚未被压缩的原样消息数 — 用于观察延迟压缩缓冲堆积情况. */
    public synchronized int pendingSize() {
        return archive.size() - existingSummaryCount();
    }

    /** 当前估算总 token, 用于观察 / 测试. */
    public synchronized int estimatedTokensSnapshot() { return estimatedTokens(); }

    // ------------------------------------------------------------------

    /**
     * 把 recent 中超出 keepCycles 的最老 cycle 整段挪到 archive 末尾.
     * 这一步**不调 LLM**, 只是搬家.
     */
    private void spillOverflowToArchive() {
        int keepFromIdx = findKeepFromIndex(recent, keepCycles);
        if (keepFromIdx <= 0) return;
        archive.addAll(recent.subList(0, keepFromIdx));
        recent.subList(0, keepFromIdx).clear();
    }

    /** archive 是否含未压缩 raw 消息(头部已压缩 SystemMessage 不算). */
    private boolean hasRawInArchive() {
        return archive.size() > existingSummaryCount();
    }

    /**
     * archive 中累计 tool call 次数, 用 ToolExecutionResultMessage 为锚点计数 —
     * parallel tool calling 下一个 AiMessage 可能产生多条 ToolResult, 按 ToolResult 数算
     * 与"上下文真正堆了多少"线性相关. 已压缩摘要 SystemMessage 不计.
     *
     * 注: spillOverflowToArchive / findKeepFromIndex 仍按 AiMessage 锚点切 ReAct 周期,
     * 保证 spill 时 cycle 边界对齐 (孤儿 ToolResult 会让 OpenAI 兼容 API 报错).
     * 压缩触发判断用 tool call 数, spill 用 cycle 数, 两个粒度各取所长.
     */
    private int toolCallsInArchive() {
        int prefix = existingSummaryCount();
        int n = 0;
        for (int i = prefix; i < archive.size(); i++) {
            if (archive.get(i) instanceof ToolExecutionResultMessage) n++;
        }
        return n;
    }

    /**
     * archive 头部已压缩摘要 SystemMessage 的数量(0 或 1).
     * 通过文本前缀 SUMMARY_PREFIX 识别.
     */
    private int existingSummaryCount() {
        if (!archive.isEmpty() && archive.get(0) instanceof SystemMessage sm
                && sm.text() != null && sm.text().startsWith(SUMMARY_PREFIX)) {
            return 1;
        }
        return 0;
    }

    /**
     * 真正调 LLM 压缩 archive — **包括头部已压缩摘要 + 新增 raw 一起送 LLM 重压**.
     * 输出单一新摘要 SystemMessage 替换整个 archive.
     *
     * recent 不动. 失败时写降级占位, archive 仍清空原 raw(保留旧摘要).
     */
    private void compressArchive() {
        int prefixCount = existingSummaryCount();
        String existingText = prefixCount == 1
                ? ((SystemMessage) archive.get(0)).text().substring(SUMMARY_PREFIX.length())
                : null;
        List<ChatMessage> rawCycles = new ArrayList<>(archive.subList(prefixCount, archive.size()));

        String newSummary;
        try {
            newSummary = summarizer.summarize(rawCycles, existingText);
        } catch (RuntimeException e) {
            log.warn("history-summarizer 调用失败, 待压缩 {} 条消息将以降级占位标记: {}",
                    rawCycles.size(), e.toString());
            newSummary = appendTruncationNote(existingText, rawCycles.size());
        }

        archive.clear();
        if (newSummary != null && !newSummary.isBlank()) {
            archive.add(SystemMessage.from(SUMMARY_PREFIX + newSummary));
        }
        // 注意: 这里**不动** recent. recent 的生命周期只由 spillOverflowToArchive 管理.
    }

    /**
     * 估算 ChatMemory 当前会拼出的 prompt 总 token.
     * 启发式: 字符数 × 0.4(中英文混合保守值, 偏高估让压缩稍微提前触发).
     */
    private int estimatedTokens() {
        int sum = 0;
        if (originalSystem != null) sum += approxTokens(originalSystem.text());
        if (userMessage != null) sum += approxTokens(textOf(userMessage));
        for (ChatMessage m : archive) sum += approxTokens(messageTextFor(m));
        for (ChatMessage m : recent) sum += approxTokens(messageTextFor(m));
        return sum;
    }

    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) Math.ceil(s.length() * 0.4);
    }

    private static String textOf(UserMessage um) {
        return um.singleText() == null ? "" : um.singleText();
    }

    private static String messageTextFor(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            StringBuilder sb = new StringBuilder();
            if (ai.text() != null) sb.append(ai.text());
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    sb.append(' ').append(req.name()).append('(').append(req.arguments()).append(')');
                }
            }
            return sb.toString();
        }
        if (m instanceof ToolExecutionResultMessage tm) {
            return tm.text() == null ? "" : tm.text();
        }
        if (m instanceof SystemMessage sm) {
            return sm.text() == null ? "" : sm.text();
        }
        return "";
    }

    private static String appendTruncationNote(String existing, int droppedCount) {
        String note = "(LLM 摘要失败, 早期 " + droppedCount + " 条消息已截断不可见)";
        if (existing == null || existing.isBlank()) return note;
        return existing + "\n" + note;
    }

    /**
     * 从后往前找第 K 个"带 tool requests 的 AiMessage", 返回它在 all 中的下标.
     * 若总周期数 < K, 返回 0(都保留).
     */
    static int findKeepFromIndex(List<ChatMessage> all, int k) {
        if (k <= 0 || all.isEmpty()) return 0;
        int cyclesSeen = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessage m = all.get(i);
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                cyclesSeen++;
                if (cyclesSeen >= k) return i;
            }
        }
        return 0;
    }

    static List<ChatMessage> trimToLastKCycles(List<ChatMessage> all, int k) {
        if (k <= 0 || all.isEmpty()) return new ArrayList<>(all);
        int keepFromIdx = findKeepFromIndex(all, k);
        return new ArrayList<>(all.subList(keepFromIdx, all.size()));
    }
}
