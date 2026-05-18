package com.slowsql.agent.diagnosis.tools;

import com.slowsql.agent.dbinspect.ToolBackend;
import com.slowsql.agent.diagnosis.tools.result.ExplainResult;
import com.slowsql.agent.diagnosis.tools.result.TableInfoResult;
import com.slowsql.agent.diagnosis.tools.result.VerifyResult;
import com.slowsql.agent.diagnosis.tools.result.ToolJson;

import com.slowsql.agent.diagnosis.memory.KeyFact;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 单 case 内每个工具的累计调用上限. 超过后工具直接返回 tool_call_limit_exceeded
     * 引导 LLM 降级, 防"verify_fail → 改写 → 又 fail" 这类 ReAct 死循环耗尽 30 轮.
     *
     * 取值依据 (dj_006 实测 trace, 5表 JOIN deferred_join 典型路径):
     *   - getTableInfo: 6 张表 schema 收集 + 4 次容错回查 buffer = 10
     *   - runExplain:   原 SQL + 至多 5 次改写预检 buffer = 6
     *   - verify:       3 次改写重试上限 — 第 3 次仍 fail 说明改写思路不对, 应降级 unsupported
     *   - recallFacts:  5 次刷新足够覆盖全程, 多了说明 LLM 在打转
     */
    public static final int LIMIT_GET_TABLE_INFO = 10;
    public static final int LIMIT_RUN_EXPLAIN = 6;
    public static final int LIMIT_VERIFY = 3;
    public static final int LIMIT_RECALL_FACTS = 5;

    private final ToolBackend backend;
    private final AgentStatsListener stats;
    private final KeyFactStore factStore;
    private final Map<String, Integer> callCount = new ConcurrentHashMap<>();

    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats, KeyFactStore factStore) {
        this.backend = Objects.requireNonNull(backend);
        this.stats = Objects.requireNonNull(stats);
        this.factStore = Objects.requireNonNull(factStore);
    }

    /** 兼容旧调用 — 用空 KeyFactStore. 仅用于不需要 recallFacts 的 mock 链路. */
    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats) {
        this(backend, stats, new KeyFactStore());
    }

    @Tool("返回指定表的 schema 与索引信息. 输出 JSON: " +
            "{status:'ok'|'error', table, create_table, indexes:[{name,unique,columns,cardinality}], " +
            "estimated_rows, row_count_note}. 在判断主键/索引/字段类型前必须先调.")
    public String getTableInfo(
            @P("表名, 例如 orders / users / products") String tableName) {
        stats.onToolCall("getTableInfo", fp(tableName));
        checkLimit("getTableInfo", LIMIT_GET_TABLE_INFO);
        try {
            TableInfoResult r = backend.describeTable(tableName);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
            }
            return r.toJson();
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            return TableInfoResult.error("internal_error", tableName).toJson();
        }
    }

    @Tool("返回给定 SQL 的 EXPLAIN 结果. 输出 JSON: " +
            "{status:'ok'|'error', rows:[{id, select_type, table, type, key, rows, extra, ...}]}. " +
            "用于确认是否真的命中深分页扫描.")
    public String runExplain(
            @P("待诊断的 SQL, 必须是合法可解析的查询语句") String sql) {
        stats.onToolCall("runExplain", fp(sql));
        checkLimit("runExplain", LIMIT_RUN_EXPLAIN);
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

    @Tool("校验改写正确性. 工具按改写形态自动分流, 你只调一次, 不需要传策略参数.\n" +
            "输出 JSON 的字段按 strategy 决定:\n" +
            "\n" +
            "  改写仍含 OFFSET → strategy='row_hash' (双跑前 100 行规范化 hash 比对)\n" +
            "    pass: {status:'pass', strategy, row_hash_subtype, sampled_rows, rewritten_plan}\n" +
            "    fail: {status:'fail', strategy, reason∈{row_count_diff|content_mismatch|order_mismatch},\n" +
            "           row_hash_subtype, sampled_rows, first_diff_row_index, diff_row_count, hint}\n" +
            "\n" +
            "  原 SQL 含 OFFSET 但改写消除了 OFFSET → strategy='cursor_plan_validity' (校验改写 plan 健康性)\n" +
            "    pass: {status:'pass', strategy, rewritten_plan, original_plan(可能缺),\n" +
            "           rewritten_rows_estimate, original_rows_estimate(可能缺), rows_reduction_pct(可能缺), warnings}\n" +
            "    fail: {status:'fail', strategy, reason∈{missing_order_by|cursor_plan_invalid|explain_returned_empty},\n" +
            "           rewritten_plan, warnings, hint}\n" +
            "\n" +
            "  任意 error: {status:'error', reason, message, hint}\n" +
            "\n" +
            "注意: cursor 改写的游标谓词必须填具体示例值(如 'id > 0'), 不要用 '?' / '${last_id}', " +
            "否则 EXPLAIN 报 syntax_error.")
    public String verifyResultEquivalence(
            @P("原始 SQL") String originalSql,
            @P("改写后的 SQL") String rewrittenSql) {
        stats.onToolCall("verifyResultEquivalence", fp(rewrittenSql));
        checkLimit("verifyResultEquivalence", LIMIT_VERIFY);
        try {
            VerifyResult r = backend.verifyEquivalence(originalSql, rewrittenSql);
            // error 不算 pass 也不算 fail, 只上报失败原因 — 让评测层区分"agent 写得太烂导致 verify 跑不起来"
            // 与"verify 跑了但结果不等价"
            if (r.isError()) {
                stats.onToolFailure(r.reason());
            } else {
                stats.onVerifyResult(r.isPass(), r.rowsReductionPct(), r.speedupX());
                if (r.isFail()) {
                    stats.onToolFailure("verify_fail");
                }
            }
            return r.toJson();
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            return VerifyResult.error("internal_error", e.getMessage()).toJson();
        }
    }

    @Tool("查询本次诊断过程中已累积的'关键事实'摘要 — 来自之前每次 getTableInfo / runExplain / " +
            "verifyResultEquivalence 返回时自动抽出的紧凑 fact (schema/plan/verify 三大类). " +
            "用法: 当你调过多次工具, 想用一条压缩摘要刷新记忆(而不是重读原始大 JSON), 调一次 recallFacts. " +
            "可选传 category 过滤: 'schema' / 'plan' / 'verify' / 空串=全部. " +
            "输出 JSON: {status:'ok', total_count, facts:[{category, subject, detail}, ...]}.")
    public String recallFacts(
            @P("可选 category 过滤: schema / plan / verify, 或空串取全部") String category) {
        stats.onToolCall("recallFacts", fp(category));
        checkLimit("recallFacts", LIMIT_RECALL_FACTS);
        try {
            List<KeyFact> snap = factStore.snapshot();
            if (category != null && !category.isBlank()) {
                String wanted = category.trim();
                snap = snap.stream().filter(f -> wanted.equals(f.category())).toList();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("total_count", snap.size());
            body.put("facts", snap);
            return ToolJson.toJson(body);
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("reason", "internal_error");
            err.put("message", e.getMessage());
            return ToolJson.toJson(err);
        }
    }

    /**
     * 累计调用 +1, 严格大于 limit 时抛 ToolCallLimitExceededException — 等同 LangChain4j
     * maxSequentialToolsInvocations 的硬熔断, 由 LangChain4jDiagnosisAgent 的
     * toolExecutionErrorHandler 重抛后冒泡, 强制中断本次诊断.
     */
    private void checkLimit(String toolName, int limit) {
        int n = callCount.merge(toolName, 1, Integer::sum);
        if (n > limit) {
            throw new ToolCallLimitExceededException(toolName, limit);
        }
    }

    /**
     * 把工具参数转成短指纹, 用于"同参数重复调用"检测.
     * 用 SHA-1 前 8 字节(64-bit), 比 String.hashCode 32-bit 碰撞空间大约 40 亿倍,
     * 避免不同 SQL 撞到同一指纹污染 repeated_tool_call_rate 指标.
     */
    private static String fp(String s) {
        if (s == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            // SHA-1 必然存在, fallback 仅为编译器满意
            return Integer.toHexString(s.hashCode());
        }
    }
}
