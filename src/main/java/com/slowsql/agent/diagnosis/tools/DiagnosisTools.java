package com.slowsql.agent.diagnosis.tools;

import com.slowsql.agent.dbinspect.ToolBackend;
import com.slowsql.agent.diagnosis.tools.result.ExplainResult;
import com.slowsql.agent.diagnosis.tools.result.TableInfoResult;
import com.slowsql.agent.diagnosis.tools.result.VerifyResult;
import com.slowsql.agent.diagnosis.tools.result.ToolJson;

import com.slowsql.agent.diagnosis.memory.KeyFact;
import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import com.slowsql.agent.tracing.ToolCallEvent;
import com.slowsql.agent.tracing.TraceCollector;
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
     * 同工具 + 同参数 的累计调用上限. 第 4 次同参数调用触发硬熔断,
     * 防"用相同 SQL 反复试 verify"这类真死循环.
     *
     * 设计语义: 按"toolName + 参数指纹"独立计数 — LLM 用 3 个不同 SQL 调 verify 各 1 次
     * 不会撞墙 (那是合理的"换思路重试"); 但用同一 SQL 反复调 ≥ 4 次说明 LLM 困在原地.
     *
     * 不再按"工具维度全局上限"算 (历史: LIMIT_VERIFY=3 全局/按工具) — 那种把不同 SQL
     * 也算累计, 会误判合法的探索为死循环, 让 LLM 写第 4 个不同改写时被直接拦住.
     */
    public static final int LIMIT_PER_TOOL_PARAM = 3;

    /**
     * verify 工具独享的"全局累计上限" — **不论 rewrittenSql 是什么**, 调用次数 > 此值抛.
     *
     * 为什么 verify 需要单独的全局上限:
     *   - verify 的参数空间无限大 (改写 SQL 任意), per-(tool, args)=3 永远拦不住"LLM 写 N 个
     *     不同改写都 fail" 这种无效探索. dj_005 实测一次跑 23 个不同改写, ~254k token, 15 分钟.
     *   - 其他工具参数空间天然有界 (getTableInfo: 表数; recallFacts: 4 个 category;
     *     runExplain: SQL 但 LLM 一般 3-5 次足够), 不需要这种全局兜底.
     *
     * 值的选取: 当前用 15 作为一个"较大但能兜住失控 case"的默认, 后续根据评测数据调.
     */
    public static final int LIMIT_VERIFY_TOTAL = 15;

    private final ToolBackend backend;
    private final AgentStatsListener stats;
    private final KeyFactStore factStore;
    private final TraceCollector trace;
    private final Map<String, Integer> callCount = new ConcurrentHashMap<>();
    /** verify 累计调用次数 (不论参数), 用于 LIMIT_VERIFY_TOTAL 兜底. */
    private int verifyTotalCount = 0;

    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats, KeyFactStore factStore,
                          TraceCollector trace) {
        this.backend = Objects.requireNonNull(backend);
        this.stats = Objects.requireNonNull(stats);
        this.factStore = Objects.requireNonNull(factStore);
        this.trace = Objects.requireNonNull(trace);
    }

    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats, KeyFactStore factStore) {
        this(backend, stats, factStore, TraceCollector.noOp());
    }

    /** 兼容旧调用 — 用空 KeyFactStore. 仅用于不需要 recallFacts 的 mock 链路. */
    public DiagnosisTools(ToolBackend backend, AgentStatsListener stats) {
        this(backend, stats, new KeyFactStore(), TraceCollector.noOp());
    }

    @Tool("返回指定表的 schema 与索引信息. 输出 JSON: " +
            "{status:'ok'|'error', table, create_table, indexes:[{name,unique,columns,cardinality}], " +
            "estimated_rows, row_count_note}. 在判断主键/索引/字段类型前必须先调.")
    public String getTableInfo(
            @P("表名, 例如 orders / users / products") String tableName) {
        ToolCall call = new ToolCall("getTableInfo", fp(tableName), argsJson("tableName", tableName));
        try {
            call.start();
            TableInfoResult r = backend.describeTable(tableName);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
                call.markFailed(r.reason());
            }
            r.exportFactsTo(factStore);
            return call.done(r.toJson());
        } catch (ToolCallLimitExceededException e) {
            call.markFailed("tool_call_limit_exceeded");
            throw e;
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            call.markFailed("internal_error");
            return call.done(TableInfoResult.error("internal_error", tableName).toJson());
        } finally {
            call.record();
        }
    }

    @Tool("返回给定 SQL 的 EXPLAIN 结果. 输出 JSON: " +
            "{status:'ok'|'error', rows:[{id, select_type, table, type, key, rows, extra, ...}]}. " +
            "用于确认是否真的命中深分页扫描.")
    public String runExplain(
            @P("待诊断的 SQL, 必须是合法可解析的查询语句") String sql) {
        ToolCall call = new ToolCall("runExplain", fp(sql), argsJson("sql", sql));
        try {
            call.start();
            ExplainResult r = backend.explain(sql);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
                call.markFailed(r.reason());
            }
            r.exportFactsTo(factStore);
            return call.done(r.toJson());
        } catch (ToolCallLimitExceededException e) {
            call.markFailed("tool_call_limit_exceeded");
            throw e;
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            call.markFailed("internal_error");
            return call.done(ExplainResult.error("internal_error", e.getMessage()).toJson());
        } finally {
            call.record();
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
        // verify 是最关键的追因点 — args 同时记 originalSql + rewrittenSql, 一眼看 LLM 试了什么
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("originalSql", originalSql);
        args.put("rewrittenSql", rewrittenSql);
        ToolCall call = new ToolCall("verifyResultEquivalence", fp(rewrittenSql), ToolJson.toJson(args));
        try {
            call.start();
            checkVerifyTotalLimit();
            VerifyResult r = backend.verifyEquivalence(originalSql, rewrittenSql);
            if (r.isError()) {
                stats.onToolFailure(r.reason());
                call.markFailed(r.reason());
            } else {
                stats.onVerifyResult(r.isPass(), r.rowsReductionPct(), r.speedupX());
                if (r.isFail()) {
                    stats.onToolFailure("verify_fail");
                    call.markFailed("verify_fail:" + r.reason());
                }
            }
            r.exportFactsTo(factStore);
            return call.done(r.toJson());
        } catch (ToolCallLimitExceededException e) {
            call.markFailed("tool_call_limit_exceeded");
            throw e;
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            call.markFailed("internal_error");
            return call.done(VerifyResult.error("internal_error", e.getMessage()).toJson());
        } finally {
            call.record();
        }
    }

    @Tool("查询本次诊断过程中已累积的事实摘要 — 来自之前每次 getTableInfo / runExplain / " +
            "verifyResultEquivalence 返回时自动抽出的紧凑 fact (schema/plan/verify 三大类). " +
            "**调用时机**: 当你对之前已经查询过的某些数据的确切值不太清楚时使用 — 比如忘了 " +
            "某张表的 PK 列、某个索引的字段顺序, 或不确定上次 EXPLAIN 的 rows 估算. 不是常规步骤, " +
            "不要每次诊断都调一次. " +
            "可选传 category 过滤: 'schema' / 'plan' / 'verify' / 空串=全部. " +
            "输出 JSON: {status:'ok', total_count, facts:[{category, subject, detail}, ...]}.")
    public String recallFacts(
            @P("可选 category 过滤: schema / plan / verify, 或空串取全部") String category) {
        ToolCall call = new ToolCall("recallFacts", fp(category), argsJson("category", category));
        try {
            call.start();
            List<KeyFact> snap = factStore.snapshot();
            if (category != null && !category.isBlank()) {
                String wanted = category.trim();
                snap = snap.stream().filter(f -> wanted.equals(f.category())).toList();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("total_count", snap.size());
            body.put("facts", snap);
            return call.done(ToolJson.toJson(body));
        } catch (ToolCallLimitExceededException e) {
            call.markFailed("tool_call_limit_exceeded");
            throw e;
        } catch (Exception e) {
            stats.onToolFailure("internal_error");
            call.markFailed("internal_error");
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("reason", "internal_error");
            err.put("message", e.getMessage());
            return call.done(ToolJson.toJson(err));
        } finally {
            call.record();
        }
    }

    /** 单字段 args 的 JSON 序列化 helper — getTableInfo / runExplain / recallFacts 用. */
    private static String argsJson(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return ToolJson.toJson(m);
    }

    /**
     * 按 "toolName + 参数指纹" 维度累计调用 +1, 严格大于 LIMIT_PER_TOOL_PARAM 时抛
     * ToolCallLimitExceededException — 等同 LangChain4j maxSequentialToolsInvocations
     * 的硬熔断, 由 LangChain4jDiagnosisAgent 的 toolExecutionErrorHandler 重抛后冒泡,
     * 强制中断本次诊断.
     *
     * 同一工具不同参数的调用各自独立计数 — LLM 用 3 个不同 SQL 调 verify 不会撞墙,
     * 只有"用同一 SQL 反复调 verify ≥ 4 次"这种真死循环才触发.
     */
    private void checkLimit(String toolName, String argsFp) {
        String key = toolName + ":" + argsFp;
        int n = callCount.merge(key, 1, Integer::sum);
        if (n > LIMIT_PER_TOOL_PARAM) {
            throw new ToolCallLimitExceededException(toolName, LIMIT_PER_TOOL_PARAM);
        }
    }

    /**
     * verify 全局累计上限兜底 — 防"无限写不同改写"失控. 不论 rewrittenSql 是什么,
     * 同一 case 内 verify 调用累计 > LIMIT_VERIFY_TOTAL 即抛.
     */
    private void checkVerifyTotalLimit() {
        verifyTotalCount++;
        if (verifyTotalCount > LIMIT_VERIFY_TOTAL) {
            throw new ToolCallLimitExceededException("verifyResultEquivalence", LIMIT_VERIFY_TOTAL);
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

    /**
     * 单次 @Tool 方法的状态机封装 — 抽掉每个工具方法重复 6 次以上的 try/catch/finally boilerplate:
     * 起始时间戳 / argsJson / resultJson / failed 状态 / 终结写 trace event.
     *
     * 用法模板:
     * <pre>
     *   ToolCall call = new ToolCall(toolName, argsFp, argsJson);
     *   try {
     *       call.start();                       // onToolCall + checkLimit, 可能抛 ToolCallLimitExceeded
     *       Result r = backend.xxx();
     *       if (r.isError()) { stats.onToolFailure(r.reason()); call.markFailed(r.reason()); }
     *       return call.done(r.toJson());
     *   } catch (ToolCallLimitExceededException e) { call.markFailed("tool_call_limit_exceeded"); throw e; }
     *     catch (Exception e) { ...; call.markFailed("internal_error"); return call.done(errorJson); }
     *   finally { call.record(); }
     * </pre>
     *
     * 不当作 inner static 是因为它要访问外部 {@link #stats} / {@link #trace} / {@link #checkLimit},
     * 当作 inner class 让调用站点更短 (不用每次传 stats/trace 进 ctor).
     */
    private final class ToolCall {
        private final long t0 = System.nanoTime();
        private final long startedMs = trace.elapsedMs();
        private final String toolName;
        private final String argsFp;
        private final String argsJson;
        private String resultJson;
        private boolean failed;
        private String failReason;

        ToolCall(String toolName, String argsFp, String argsJson) {
            this.toolName = toolName;
            this.argsFp = argsFp;
            this.argsJson = argsJson;
        }

        /** stats 上报 + 上限检查. 拿出 ctor 让 ToolCallLimitExceededException 能被外层 try 捕获. */
        void start() {
            stats.onToolCall(toolName, argsFp);
            checkLimit(toolName, argsFp);
        }

        void markFailed(String reason) {
            this.failed = true;
            this.failReason = reason;
        }

        /** 正常路径返回工具 JSON, 同时记下 resultJson 供 record() 用. */
        String done(String json) {
            this.resultJson = json;
            return json;
        }

        /** finally 里调一次, 把累积的状态打成 ToolCallEvent 写入 trace. */
        void record() {
            trace.record(new ToolCallEvent(
                    startedMs, toolName, argsJson, resultJson,
                    (System.nanoTime() - t0) / 1_000_000, failed, failReason));
        }
    }
}
