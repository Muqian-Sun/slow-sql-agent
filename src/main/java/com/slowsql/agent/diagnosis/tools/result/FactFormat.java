package com.slowsql.agent.diagnosis.tools.result;

/**
 * fact 文本格式化工具 — 让 KeyFact.detail 在 LLM 眼里短而稠密.
 *
 * 不放 ToolJson, 那是 JSON 序列化层; 这里专门是给 FactExportable 实现共用的展示层 helper.
 */
final class FactFormat {

    private FactFormat() {}

    /** 1234567 → 1.2M, 1234 → 1.2K. 让大数压短, fact 文本不被原始数字撑爆. */
    static String compactNum(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fG", n / 1_000_000_000.0);
    }
}
