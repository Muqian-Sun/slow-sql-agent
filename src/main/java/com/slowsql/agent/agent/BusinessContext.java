package com.slowsql.agent.agent;

/**
 * 业务上下文 - 由调用方在 API 入参提供(可选),驱动 Agent 在多种改写方案中选择.
 * 例如 canModifyApi=true → 优先游标分页;canModifyApi=false → 延迟关联.
 */
public record BusinessContext(
        String scenario,            // 业务场景(可选,如 "user_order_list")
        Boolean canModifyApi,       // 业务是否可改 API 语义
        String uiPattern,           // pagination / infinite_scroll / search
        Integer qps,
        Integer slaP99Ms,
        String dataFreshness,       // realtime / minute / hour
        String additionalNotes
) {
    public static BusinessContext empty() {
        return new BusinessContext(null, null, null, null, null, null, null);
    }

    public boolean canModifyApiOrDefault(boolean defaultValue) {
        return canModifyApi != null ? canModifyApi : defaultValue;
    }
}
