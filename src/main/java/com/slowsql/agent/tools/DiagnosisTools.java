package com.slowsql.agent.tools;

import com.slowsql.agent.eval.AgentStatsListener;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Objects;

/**
 * LangChain4j @Tool 容器 — Agent 通过反射可见的三个工具.
 *
 * 设计要点:
 *   - 工具命名意图导向(getTableInfo / runExplain / verifyResultEquivalence),
 *     不绑定具体实现技术, 让 LLM 看到的是"做什么"而非"怎么做".
 *   - 真正的查询逻辑下沉到 ToolBackend, 方便 mock / 真实切换.
 *   - ToolBackend 返回结构化 record, 这层负责:
 *       (a) 序列化成 JSON 字符串送给 LLM(LLM 拿到的全是干净 JSON, 不是混着 PASS/FAIL/key=value 的杂字符串)
 *       (b) 从 record 提取 status / reason 喂给 AgentStatsListener
 *   - 每次调用同步打到 AgentStatsListener, 由评测层统计调用次数 / 重复率 / 失败原因.
 *   - 任何意外异常都吞成结构化 JSON, LLM 永远拿到能读的 string, 触发 ReAct 自纠正.
 */
public class DiagnosisTools {

    private final ToolBackend backend;
    private final AgentStatsListener stats;

    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats) {
        this.backend = Objects.requireNonNull(backend);
        this.stats = Objects.requireNonNull(stats);
    }

    @Tool("返回指定表的 schema 与索引信息. 输出 JSON: " +
            "{status:'ok'|'error', table, create_table, indexes:[{name,unique,columns,cardinality}], " +
            "estimated_rows, row_count_note}. 在判断主键/索引/字段类型前必须先调.")
    public String getTableInfo(
            @P("表名, 例如 orders / users / products") String tableName) {
        stats.onToolCall("getTableInfo", fp(tableName));
        try {
            TableInfoResult r = backend.describeTable(tableName);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
            }
            return r.toJson();
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            return VerifyResult.error("internal_error", e.getMessage()).toJson();
        }
    }

    @Tool("返回给定 SQL 的 EXPLAIN 结果. 输出 JSON: " +
            "{status:'ok'|'error', rows:[{id, select_type, table, type, key, rows, Extra, ...}]}. " +
            "用于确认是否真的命中深分页扫描.")
    public String runExplain(
            @P("待诊断的 SQL, 必须是合法可解析的查询语句") String sql) {
        stats.onToolCall("runExplain", fp(sql));
        try {
            ExplainResult r = backend.explain(sql);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
            }
            return r.toJson();
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            return ExplainResult.error("internal_error", e.getMessage()).toJson();
        }
    }

    @Tool("校验改写正确性. 工具按改写形态自动分流, 你只调一次:\n" +
            "  - 改写仍含 OFFSET → 双跑前 100 行规范化 hash 比对(row_hash 策略).\n" +
            "  - 原 SQL 含 OFFSET 但改写消除了 OFFSET → 改写 plan 健康性校验(cursor_plan_validity 策略),\n" +
            "    包括 ORDER BY 是否存在 / 是否走索引 / rows 估算是否合理.\n" +
            "输出 JSON: {status:'pass'|'fail'|'error', strategy, reason, hint, " +
            "rewritten_plan, original_plan, rewritten_rows_estimate, original_rows_estimate, " +
            "rows_reduction_pct, warnings, ...}. 注意: cursor 改写的游标谓词必须填具体示例值(如 'id > 0'), " +
            "不要用 '?' 或 '${last_id}', 否则 EXPLAIN 报 syntax_error.")
    public String verifyResultEquivalence(
            @P("原始 SQL") String originalSql,
            @P("改写后的 SQL") String rewrittenSql) {
        stats.onToolCall("verifyResultEquivalence", fp(rewrittenSql));
        try {
            VerifyResult r = backend.verifyEquivalence(originalSql, rewrittenSql);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
            } else if (r.isFail()) {
                stats.onToolFailure("verify_fail");
            }
            return r.toJson();
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            return VerifyResult.error("internal_error", e.getMessage()).toJson();
        }
    }

    /** 把工具参数转成短指纹, 用于"同参数重复调用"检测. */
    private static String fp(String s) {
        if (s == null) return "null";
        return Integer.toHexString(s.hashCode());
    }
}
