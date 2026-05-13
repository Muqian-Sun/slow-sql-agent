package com.slowsql.agent.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 diagnose() 期间的事实仓库, 配合 LayeredChatMemory 把 fact 注入到每轮 system prompt.
 *
 * 设计:
 *   - 按 (category, subject) 去重, 后写覆盖先写 (LLM 重新调同一工具拿到的更新会替换旧 fact).
 *   - 渲染时按 category 分组, 给 LLM 一个稳定可扫的结构.
 *   - 容量软上限: 16 facts, 超过后 evict 最早的 — 防 fact 暴涨打回 token 浪费.
 *   - 跨 diagnose() 不复用 (每个 case 独立).
 */
public class KeyFactStore {

    private static final int CAPACITY = 16;

    /** key = category + "|" + subject. LinkedHashMap 保留插入顺序 (用于 evict). */
    private final Map<String, KeyFact> facts = new LinkedHashMap<>();

    public synchronized void put(KeyFact f) {
        if (f == null) return;
        String key = f.category() + "|" + f.subject();
        // remove + put: 让"更新"的事实排到末尾, evict 时仍按"最早进入"丢
        facts.remove(key);
        facts.put(key, f);
        while (facts.size() > CAPACITY) {
            String oldest = facts.keySet().iterator().next();
            facts.remove(oldest);
        }
    }

    public synchronized int size() { return facts.size(); }

    public synchronized List<KeyFact> snapshot() {
        return new ArrayList<>(facts.values());
    }

    /**
     * 渲染成给 LLM 看的紧凑文本块:
     *   === 已确认事实 ===
     *   [schema]
     *     - table=orders ...
     *   [plan]
     *     - sql_fp=ab12 ...
     *   [verify]
     *     - last: PASS reduction=99.9%
     *
     * 没有 fact 时返回空串, LayeredChatMemory 据此决定是否附到 system prompt.
     */
    public synchronized String render() {
        if (facts.isEmpty()) return "";
        Map<String, List<KeyFact>> byCategory = new LinkedHashMap<>();
        for (KeyFact f : facts.values()) {
            byCategory.computeIfAbsent(f.category(), k -> new ArrayList<>()).add(f);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 已确认事实(本次诊断累计, 不必重新查) ===\n");
        for (Map.Entry<String, List<KeyFact>> e : byCategory.entrySet()) {
            sb.append('[').append(e.getKey()).append("]\n");
            for (KeyFact f : e.getValue()) {
                sb.append("  - ").append(f.subject()).append(": ").append(f.detail()).append('\n');
            }
        }
        return sb.toString();
    }
}
