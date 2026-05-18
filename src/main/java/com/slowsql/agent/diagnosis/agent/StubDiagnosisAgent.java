package com.slowsql.agent.diagnosis.agent;

import com.slowsql.agent.diagnosis.api.BusinessContext;
import com.slowsql.agent.diagnosis.api.DiagnosisAgent;
import com.slowsql.agent.diagnosis.api.DiagnosisResult;
import com.slowsql.agent.diagnosis.api.OutcomeType;

import java.util.List;
import java.util.Locale;

/**
 * 测试用的 Stub 实现,使评测基础设施可在无 LLM 环境下脱机运行.
 *
 * 简化规则(不追求精确, 只让 smoke 测有通路):
 *   - SQL 非 SELECT → UNSUPPORTED (DML 等)
 *   - 业务说明里含游标 / 可改 API 等关键字 → REWRITTEN_CURSOR
 *   - 否则 → REWRITTEN_DEFERRED_JOIN
 *
 * 真实路径选择 / 索引建议 / 越界识别由 LangChain4jDiagnosisAgent + LLM 承担, stub 不负责.
 */
public class StubDiagnosisAgent implements DiagnosisAgent {

    @Override
    public DiagnosisResult diagnose(String sql, BusinessContext context) {
        if (!isSelectLike(sql)) {
            return new DiagnosisResult(
                    OutcomeType.UNSUPPORTED,
                    null,
                    List.of("非 SELECT 语句不在改写范围"),
                    0.9,
                    List.of("DML 请走数据迁移流程"));
        }

        if (hintsCursorAllowed(context)) {
            return new DiagnosisResult(
                    OutcomeType.REWRITTEN_CURSOR,
                    "-- cursor pagination rewrite of: " + sql,
                    List.of("业务说明含游标 / 可改 API 关键字", "假设主表有 PK"),
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

    private static boolean isSelectLike(String sql) {
        if (sql == null) return false;
        String head = sql.trim().toUpperCase(Locale.ROOT);
        return head.startsWith("SELECT") || head.startsWith("WITH");
    }

    private static boolean hintsCursorAllowed(BusinessContext ctx) {
        if (ctx == null || !ctx.hasRequirement()) return false;
        String req = ctx.requirement().toLowerCase(Locale.ROOT);
        return req.contains("游标")
                || req.contains("可改 api") || req.contains("可改api")
                || req.contains("无限滚动") || req.contains("无限下拉")
                || req.contains("下拉加载")
                || req.contains("last_id")
                || req.contains("infinite_scroll");
    }
}
