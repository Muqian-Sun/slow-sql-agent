package com.slowsql.agent.tracing;

import java.util.ArrayList;
import java.util.List;

/**
 * 单 case 执行期间累积 TraceEvent 的容器. 每个 case 一个独立实例 (跟 AgentStatsListener
 * 同生命周期, 由 EvalRunner / Controller 创建).
 *
 * 线程安全: 当前 LangChain4j AiServices 单 case 跑在同一线程, 但 ChatModelListener 回调
 * 可能跨线程, synchronized 给个保护. 不用 CopyOnWriteArrayList 因为读极少 / 写较多.
 *
 * NoOp 实例: 用 {@link #noOp()} 拿到一个永远不记事件的 collector, 给不需要 trace
 * 的链路 (单测 / smoke run) 用, 调用方不用判 null.
 */
public class TraceCollector {

    private static final TraceCollector NO_OP = new TraceCollector(true);

    private final boolean noOp;
    private final long startNanos = System.nanoTime();
    private final List<TraceEvent> events = new ArrayList<>();

    public TraceCollector() {
        this(false);
    }

    private TraceCollector(boolean noOp) {
        this.noOp = noOp;
    }

    /** 共享 noOp 实例, 给不关心 trace 的调用方默认注入. */
    public static TraceCollector noOp() {
        return NO_OP;
    }

    /** 自 case 开始的相对时间 (毫秒). 在 record event 时用作 elapsedMs. */
    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 记录一个事件. noOp 实例下静默忽略. */
    public synchronized void record(TraceEvent event) {
        if (noOp) return;
        events.add(event);
    }

    /** 不可变快照. 用于 RunTrace 落盘时取数据. */
    public synchronized List<TraceEvent> snapshot() {
        return List.copyOf(events);
    }

    public boolean isNoOp() { return noOp; }
    public int size() { return events.size(); }
}
