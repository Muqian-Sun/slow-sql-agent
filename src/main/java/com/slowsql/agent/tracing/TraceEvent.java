package com.slowsql.agent.tracing;

/**
 * 单 case 执行过程中的事件. sealed 接口让 Jackson 能按 type 字段反序列化.
 *
 * 当前实现的事件类型聚焦"事后追因黑洞"问题:
 *   - ToolCallEvent: 每次工具调用的完整 args/result + 耗时 + 失败原因 —
 *     直接解决"dj_006 verify_fail 3 次但看不到具体改写 SQL"这类追因黑洞.
 *   - 后续如有需要可扩展 LlmCallEvent / MemorySpillEvent / SummarizerEvent
 *     (sealed interface 提供扩展点, 当前不引入避免过度设计).
 */
public sealed interface TraceEvent permits ToolCallEvent {

    /** 事件类型标识, 给 JSON 序列化的 type discriminator 用. */
    String type();

    /** 自 case 开始的相对时间 (毫秒), 给读 trace 时排序 / 算前后耗时用. */
    long elapsedMs();
}
