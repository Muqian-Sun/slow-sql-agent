package com.slowsql.agent.agent;

import java.util.List;

/**
 * 测试用的 Stub 实现,使评测基础设施可在无 LLM 环境下脱机运行.
 *
 * 简单规则:
 * - LIMIT offset > 10000 → REWRITTEN_DEFERRED_JOIN
 * - 业务允许改 API → REWRITTEN_CURSOR
 * - 否则 NO_OPTIMIZATION_NEEDED
 */
public class StubDiagnosisAgent implements DiagnosisAgent {

    @Override
    public DiagnosisResult diagnose(String sql, BusinessContext context) {
        long offset = extractLimitOffset(sql);

        if (offset < 10_000) {
            return new DiagnosisResult(
                    OutcomeType.NO_OPTIMIZATION_NEEDED,
                    null,
                    List.of("offset 不属深分页范畴"),
                    0.9,
                    List.of());
        }

        boolean canCursor = context != null && Boolean.TRUE.equals(context.canModifyApi());
        if (canCursor) {
            return new DiagnosisResult(
                    OutcomeType.REWRITTEN_CURSOR,
                    "-- cursor pagination rewrite of: " + sql,
                    List.of("假设业务可改 API 语义", "假设主表有 PK"),
                    0.9,
                    List.of("游标分页要求前端传 last_id"));
        }

        return new DiagnosisResult(
                OutcomeType.REWRITTEN_DEFERRED_JOIN,
                "-- deferred-join rewrite of: " + sql,
                List.of("假设主表有 PRIMARY KEY", "假设 ORDER BY 可作主键稳定排序"),
                0.85,
                List.of());
    }

    private long extractLimitOffset(String sql) {
        // 极简正则解析,生产实现走 Druid AST
        try {
            String upper = sql.toUpperCase();
            int limitIdx = upper.lastIndexOf("LIMIT");
            if (limitIdx < 0) return 0;
            String tail = sql.substring(limitIdx + 5).trim();
            if (tail.contains(",")) {
                return Long.parseLong(tail.split(",")[0].trim());
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
