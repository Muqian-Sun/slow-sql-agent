package com.slowsql.agent.dbinspect;

import com.slowsql.agent.diagnosis.tools.result.ExplainResult;
import com.slowsql.agent.diagnosis.tools.result.TableInfoResult;
import com.slowsql.agent.diagnosis.tools.result.VerifyResult;
import com.slowsql.agent.diagnosis.tools.HintCatalog;
import com.slowsql.agent.diagnosis.tools.ErrorCategory;
import com.slowsql.agent.diagnosis.tools.SqlSafety;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 真实 JDBC 工具后端 — 把 ToolBackend 的三个抽象方法落到 MySQL.
 *
 * 安全约束:
 *   - DataSource 由 DataSourceFactory 配 readOnly = true, 物理上拒绝写.
 *   - SQL 入口经 SqlSafety 二次过滤, 拒非 SELECT/WITH 与多语句.
 *   - PreparedStatement 设 queryTimeout + maxRows, 防止超大结果集 / 死循环.
 *
 * 返回 record 约定:
 *   每个工具方法返回类型化 record(TableInfoResult / ExplainResult / VerifyResult),
 *   由 DiagnosisTools 序列化为 JSON 发给 LLM, 同时提取 status 落到 stats.
 *
 * verify 等价性策略 — 按改写形态自动分流(LLM 不感知, 仍叫一个工具):
 *
 *   策略 A: 行级 hash(DEFERRED_JOIN_HASH / GENERAL_HASH)
 *     适用: 改写仍保留 OFFSET(典型 deferred_join), 或两边都无 OFFSET(语义应字节级一致).
 *     做法: 双跑两条 SQL, 各取前 100 行, 按 JDBC 列类型规范化后 SHA-256 逐行比对.
 *     额外: 通过后 EXPLAIN 一次改写 SQL, 把 plan 摘要嵌进结果, 让 LLM 自查改写是否真变快.
 *     类型规范化:
 *       DATETIME/DATE/TIME → epoch millis(避开 driver 字符串格式差异)
 *       DECIMAL/NUMERIC → stripTrailingZeros().toPlainString()
 *       FLOAT/DOUBLE → 固定 6 位小数(避开二进制浮点精度差)
 *       CHAR/VARCHAR → 去尾 ASCII 空格(CHAR padding 归一)
 *       BOOLEAN → 0/1
 *       BLOB → 字节 SHA-256
 *       NULL → SOH(0x01) 哨兵, 不会跟任何字符串值冲突
 *
 *   策略 B: 计划校验(CURSOR_PLAN)
 *     适用: 原 SQL 含 OFFSET 但改写消除了 OFFSET(典型 cursor 改写,
 *           WHERE pk </>? + LIMIT n), 行级等价天然不成立.
 *     做法: EXPLAIN 改写 SQL, 校验 plan 健康性:
 *       hard fail: type=ALL(全表扫) / key=NULL(未走索引) / 改写缺 ORDER BY
 *       soft warn: Extra 含 "Using filesort" / rows_estimate 超 100k
 *     额外: 同时 EXPLAIN 原 SQL, 算 rows_reduction_pct, 让 LLM / 评测层拿到真实改进幅度.
 *     语义正确性靠 assumptions 兜底(必须显式声明 cursor 业务约定).
 *
 *   row_hash 双跑用同一 Connection 包成单事务 (REPEATABLE READ), 两次 SELECT 同一 consistent
 *   read-view, 避免并发写引起的 MVCC 漂移误判. 跑完直接 rollback (verify 路径无写入).
 */
public class JdbcToolBackend implements ToolBackend {

    /** 行 hash 时字段间的分隔符, 选用 ASCII Unit Separator (0x1F), 不会出现在常规数据里. */
    private static final char FIELD_SEP = '';

    /** NULL 哨兵, 用 SOH 控制字符 (0x01), 不会出现在任何 UTF-8 文本数据里, 避免与字符串值冲突. */
    private static final char NULL_SENTINEL = '';

    /**
     * 双跑等价性校验只取结果集前 N 行做 hash. 100 是工程平衡点:
     *   - 深分页改写本身 LIMIT 通常 20-50, 100 行覆盖典型页大小
     *   - 行级 hash 任一行不一致即 FAIL, 不需要全量扫
     *   - 大结果集场景留性能空间(verify 不应成为评测瓶颈)
     */
    private static final int VERIFY_TOP_N = 100;

    /** OFFSET 检测: 既匹配 "LIMIT m, n" 也匹配 "OFFSET m", 用于判定改写形态. */
    private static final Pattern OFFSET_PATTERN = Pattern.compile(
            "\\bOFFSET\\s+\\d+|\\bLIMIT\\s+\\d+\\s*,\\s*\\d+",
            Pattern.CASE_INSENSITIVE);

    /** ORDER BY 检测: cursor 改写必须含 ORDER BY 才能保证游标语义可重复. */
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile(
            "\\bORDER\\s+BY\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * cursor plan 校验时 rows 估算 soft warn 阈值. 注: MySQL 的 EXPLAIN.rows 报的是
     * range 内扫描行数估算, 不是 LIMIT 后真实返回行数, 因此只 warn, 不 hard fail.
     */
    private static final long CURSOR_PLAN_ROWS_WARN_THRESHOLD = 100_000L;

    /** 改写形态分类, 决定 verify 走哪条路径. */
    private enum VerifyStrategy {
        DEFERRED_JOIN_HASH, // 改写仍含 OFFSET → 行级 hash
        CURSOR_PLAN,        // 原 OFFSET 被消除 → plan 健康性
        GENERAL_HASH        // 两边都无 OFFSET → 行级 hash
    }

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    private final int maxRows;

    public JdbcToolBackend(DataSource dataSource, JdbcConfig config) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = (int) config.queryTimeout().toSeconds();
        this.maxRows = config.maxRows();
    }

    // ------------------------------------------------------------------
    // 1) getTableInfo: SHOW CREATE TABLE + 索引摘要 + 行数估算
    // ------------------------------------------------------------------
    @Override
    public TableInfoResult describeTable(String tableName) {
        if (!SqlSafety.isValidIdentifier(tableName)) {
            return TableInfoResult.error("invalid_identifier", tableName);
        }
        try (Connection conn = dataSource.getConnection()) {
            String createTable = showCreateTable(conn, tableName);
            if (createTable == null) {
                return TableInfoResult.error("not_found", tableName);
            }
            List<TableInfoResult.IndexEntry> indexes = indexEntries(conn, tableName);
            long estimatedRows = tableRowsEstimate(conn, tableName);
            return TableInfoResult.ok(tableName, createTable, indexes, estimatedRows);
        } catch (SQLSyntaxErrorException e) {
            return TableInfoResult.error("not_found", tableName);
        } catch (SQLTimeoutException e) {
            return TableInfoResult.error("query_timeout", tableName);
        } catch (SQLException e) {
            return TableInfoResult.error("internal_error", tableName);
        }
    }

    // ------------------------------------------------------------------
    // 2) runExplain: EXPLAIN <sql> → 行 list
    // ------------------------------------------------------------------
    @Override
    public ExplainResult explain(String sql) {
        String reject = SqlSafety.rejectIfUnsafe(sql);
        if (reject != null) {
            return ExplainResult.error("safety_rejected", reject);
        }
        try (Connection conn = dataSource.getConnection()) {
            return ExplainResult.ok(runExplainAsList(conn, stripTrailingSemicolon(sql)));
        } catch (SQLSyntaxErrorException e) {
            return ExplainResult.error("syntax_error", e.getMessage());
        } catch (SQLTimeoutException e) {
            return ExplainResult.error("query_timeout", e.getMessage());
        } catch (SQLException e) {
            return ExplainResult.error("internal_error", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 3) verifyResultEquivalence: 按改写形态分流
    // ------------------------------------------------------------------
    @Override
    public VerifyResult verifyEquivalence(String originalSql, String rewrittenSql) {
        String reject1 = SqlSafety.rejectIfUnsafe(originalSql);
        if (reject1 != null) return VerifyResult.error("original_sql_unsafe", reject1);
        String reject2 = SqlSafety.rejectIfUnsafe(rewrittenSql);
        if (reject2 != null) return VerifyResult.error("rewritten_sql_unsafe", reject2);

        VerifyStrategy strategy = detectStrategy(originalSql, rewrittenSql);
        try (Connection conn = dataSource.getConnection()) {
            // row_hash 双跑必须在同一 InnoDB consistent read-view 内 (REPEATABLE READ + 单事务),
            // 否则两次 executeQuery 各拿独立快照, 期间任何并发写都会让 row_hash 误判 content_mismatch.
            // cursor_plan_validity 路径只做 EXPLAIN, 也不写, 同样 rollback 兜底.
            int originalIsolation = conn.getTransactionIsolation();
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            try {
                return switch (strategy) {
                    case CURSOR_PLAN -> verifyCursorPlan(conn, originalSql, rewrittenSql);
                    case DEFERRED_JOIN_HASH, GENERAL_HASH ->
                            verifyByRowHash(conn, originalSql, rewrittenSql, strategy);
                };
            } finally {
                try { conn.rollback(); } catch (SQLException ignored) { /* 只读路径, rollback 失败不影响结果 */ }
                try { conn.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                try { conn.setTransactionIsolation(originalIsolation); } catch (SQLException ignored) {}
            }
        } catch (SQLSyntaxErrorException e) {
            return VerifyResult.error("syntax_error", e.getMessage());
        } catch (SQLTimeoutException e) {
            return VerifyResult.error("query_timeout", e.getMessage());
        } catch (SQLException e) {
            return VerifyResult.error("internal_error", e.getMessage());
        }
    }

    private static VerifyStrategy detectStrategy(String originalSql, String rewrittenSql) {
        boolean origHasOffset = hasOffsetClause(originalSql);
        boolean newHasOffset = hasOffsetClause(rewrittenSql);
        if (newHasOffset) return VerifyStrategy.DEFERRED_JOIN_HASH;
        if (origHasOffset) return VerifyStrategy.CURSOR_PLAN;
        return VerifyStrategy.GENERAL_HASH;
    }

    private static boolean hasOffsetClause(String sql) {
        return sql != null && OFFSET_PATTERN.matcher(sql).find();
    }

    private static boolean hasOrderByClause(String sql) {
        return sql != null && ORDER_BY_PATTERN.matcher(sql).find();
    }

    /**
     * 行级 hash 路径(策略 A): 行 hash 通过后顺手 EXPLAIN 改写 SQL,
     * 让 LLM 不仅看到"等价", 还看到"plan 是否真变快".
     */
    private VerifyResult verifyByRowHash(Connection conn, String originalSql, String rewrittenSql,
                                        VerifyStrategy strategy) throws SQLException {
        String subtype = strategy == VerifyStrategy.DEFERRED_JOIN_HASH ? "deferred_join" : "general";

        // 双跑顺手计时. nanoTime 精度足够区分 ms 级差异;
        // 转 ms 后写入 result, ns 留作 speedup 比值计算(避免 ms 取整后除零).
        long t0Orig = System.nanoTime();
        List<String> origHashes = runAndHashRows(conn, stripTrailingSemicolon(originalSql));
        long origNs = System.nanoTime() - t0Orig;

        long t0New = System.nanoTime();
        List<String> newHashes = runAndHashRows(conn, stripTrailingSemicolon(rewrittenSql));
        long newNs = System.nanoTime() - t0New;

        Long origMs = origNs / 1_000_000;
        Long newMs = newNs / 1_000_000;
        Double speedup = (newNs <= 0 || origNs <= 0) ? null : (double) origNs / newNs;

        if (origHashes.size() != newHashes.size()) {
            return VerifyResult.failRowHash(
                    "row_count_diff", subtype, Math.max(origHashes.size(), newHashes.size()),
                    null, null,
                    String.format("original=%d rows vs rewritten=%d rows in top %d",
                            origHashes.size(), newHashes.size(), VERIFY_TOP_N),
                    origMs, newMs, speedup);
        }
        int n = origHashes.size();
        int firstDiff = -1;
        int diffCount = 0;
        for (int i = 0; i < n; i++) {
            if (!origHashes.get(i).equals(newHashes.get(i))) {
                if (firstDiff < 0) firstDiff = i;
                diffCount++;
            }
        }
        if (diffCount == 0) {
            // 通过 → 双跑 EXPLAIN, 拉两边 plan + 算 reduction, 与 cursor_plan_validity 路径对齐
            List<VerifyResult.PlanRow> rewrittenPlan = safeExplainPlan(conn, rewrittenSql);
            List<VerifyResult.PlanRow> originalPlan = safeExplainPlan(conn, originalSql);
            long rewrittenRowsEst = sumPlanRows(rewrittenPlan);
            Long originalRowsEst = originalPlan == null ? null : sumPlanRows(originalPlan);
            Double reductionPct = computeReductionPct(originalRowsEst, rewrittenRowsEst);
            return VerifyResult.passRowHash(subtype, n, rewrittenPlan, originalPlan,
                    rewrittenRowsEst, originalRowsEst, reductionPct,
                    origMs, newMs, speedup);
        }
        // 顺序不一致但集合可能相等 → 提示 LLM 检查 ORDER BY 稳定性
        List<String> sortedOrig = new ArrayList<>(origHashes);
        List<String> sortedNew = new ArrayList<>(newHashes);
        sortedOrig.sort(String::compareTo);
        sortedNew.sort(String::compareTo);
        if (sortedOrig.equals(sortedNew)) {
            return VerifyResult.failRowHash("order_mismatch", subtype, n, firstDiff, diffCount,
                    String.format("rows=%d, diff_count=%d, set equal but order differs", n, diffCount),
                    origMs, newMs, speedup);
        }
        return VerifyResult.failRowHash("content_mismatch", subtype, n, firstDiff, diffCount,
                String.format("rows=%d, first_diff_at_index=%d, diff_count=%d", n, firstDiff, diffCount),
                origMs, newMs, speedup);
    }

    /**
     * Plan 校验路径(策略 B, cursor 专用):
     *   1. 改写必须含 ORDER BY (否则游标语义不可重复)
     *   2. EXPLAIN 改写 SQL, 检查 type / key
     *   3. EXPLAIN 原 SQL, 算 rows_reduction_pct
     *   4. 真跑一次改写 SQL 测 rewritten_latency_ms
     *      (cursor 改写形态必然是 PK 区间扫 + LIMIT n, 永远 ms 级, 安全;
     *       不真测原 SQL — 那是要被绕开的 deep offset 慢查询, 测它没意义且会卡 timeout)
     */
    private VerifyResult verifyCursorPlan(Connection conn, String originalSql, String rewrittenSql)
            throws SQLException {
        // 1) ORDER BY 强制(P1 修复) — hint 走 HintCatalog 兜底
        if (!hasOrderByClause(rewrittenSql)) {
            return VerifyResult.failCursorPlan("missing_order_by", null, null, null);
        }

        // 2) 跑改写 plan
        List<VerifyResult.PlanRow> rewrittenPlan;
        try {
            rewrittenPlan = explainPlanRows(conn, rewrittenSql);
        } catch (SQLSyntaxErrorException e) {
            return VerifyResult.error("syntax_error", e.getMessage());
        }
        if (rewrittenPlan.isEmpty()) {
            return VerifyResult.failCursorPlan("explain_returned_empty",
                    "改写 SQL EXPLAIN 输出为空, 可能不是合法查询", null, null);
        }

        List<String> hardFailures = new ArrayList<>();
        List<String> softWarnings = new ArrayList<>();
        long rewrittenRowsEst = 0;
        for (VerifyResult.PlanRow p : rewrittenPlan) {
            rewrittenRowsEst += p.rows() == null ? 0 : Math.max(p.rows(), 0);
            if ("ALL".equalsIgnoreCase(p.type())) {
                hardFailures.add("table=" + nz(p.table()) + " 全表扫(type=ALL)");
            }
            if (p.key() == null || p.key().isBlank()) {
                hardFailures.add("table=" + nz(p.table()) + " 未走索引(key=NULL)");
            }
            if (p.extra() != null && p.extra().contains("Using filesort")) {
                softWarnings.add("table=" + nz(p.table()) + " Using filesort");
            }
        }
        if (rewrittenRowsEst > CURSOR_PLAN_ROWS_WARN_THRESHOLD) {
            softWarnings.add(String.format(
                    "rewritten rows_estimate=%d 偏大(参考阈值 %d), 可能 range 估算等价于全表",
                    rewrittenRowsEst, CURSOR_PLAN_ROWS_WARN_THRESHOLD));
        }

        if (!hardFailures.isEmpty()) {
            return VerifyResult.failCursorPlan(
                    "cursor_plan_invalid",
                    String.join("; ", hardFailures),
                    rewrittenPlan,
                    softWarnings);
        }

        // 3) 拉原 SQL plan 算 reduction(失败不阻塞 PASS, null 让 Jackson 跳过)
        List<VerifyResult.PlanRow> originalPlan = safeExplainPlan(conn, originalSql);
        Long originalRowsEst = originalPlan == null ? null : sumPlanRows(originalPlan);
        Double reductionPct = computeReductionPct(originalRowsEst, rewrittenRowsEst);

        // 4) 真跑改写 SQL 一次测 latency. 失败 / 超时不阻塞 PASS, 退回 null.
        Long rewrittenLatencyMs = safeTimeRewrittenExecution(conn, rewrittenSql);

        return VerifyResult.passCursorPlan(
                rewrittenPlan, originalPlan,
                rewrittenRowsEst, originalRowsEst, reductionPct,
                softWarnings,
                rewrittenLatencyMs);
    }

    /**
     * 真跑一次改写 SQL, 测 wall-clock 耗时. 用于 cursor 路径给 DBA 看"改写 SQL 实际跑多快".
     * cursor 改写形态必然是 `WHERE pk > <const> ORDER BY pk LIMIT n` — PK 区间扫 + 早停, 必 ms 级.
     * setQueryTimeout(10s) 兜底, 超时 / 异常都返回 null 让 Jackson 跳过.
     */
    private Long safeTimeRewrittenExecution(Connection conn, String rewrittenSql) {
        try (Statement st = conn.createStatement()) {
            applyLimits(st);
            st.setMaxRows(VERIFY_TOP_N);   // 与 row_hash 路径口径对齐
            long t0 = System.nanoTime();
            try (ResultSet rs = st.executeQuery(stripTrailingSemicolon(rewrittenSql))) {
                int n = 0;
                while (rs.next() && n < VERIFY_TOP_N) n++;
            }
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (SQLException e) {
            return null;
        }
    }

    /** plan 中各表 rows 估算求和(null 当 0); 用于 reduction% 计算. */
    private static long sumPlanRows(List<VerifyResult.PlanRow> plan) {
        if (plan == null) return 0;
        return plan.stream()
                .mapToLong(p -> p.rows() == null ? 0 : Math.max(p.rows(), 0))
                .sum();
    }

    /**
     * reduction% = (original - rewritten) / original × 100.
     * 原行数缺失或 ≤ 0 → 返回 null (Jackson 跳过, 不压成 0 误导 LLM "无改进").
     * 可能为负 (改写更糟), 据实返回.
     */
    private static Double computeReductionPct(Long originalRows, long rewrittenRows) {
        if (originalRows == null || originalRows <= 0) return null;
        return 100.0 * (originalRows - rewrittenRows) / originalRows;
    }

    /** 安全 EXPLAIN: 任何错误返回 null, 用于"补充信息但不阻塞主路径"的场景. */
    private List<VerifyResult.PlanRow> safeExplainPlan(Connection conn, String sql) {
        try {
            return explainPlanRows(conn, sql);
        } catch (SQLException e) {
            return null;
        }
    }

    private List<VerifyResult.PlanRow> explainPlanRows(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            applyLimits(st);
            try (ResultSet rs = st.executeQuery("EXPLAIN " + stripTrailingSemicolon(sql))) {
                return parseExplainAsPlanRows(rs);
            }
        }
    }

    private static List<VerifyResult.PlanRow> parseExplainAsPlanRows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            idx.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
        }
        List<VerifyResult.PlanRow> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new VerifyResult.PlanRow(
                    colString(rs, idx, "table"),
                    colString(rs, idx, "type"),
                    colString(rs, idx, "key"),
                    colLongOrNull(rs, idx, "rows"),
                    colString(rs, idx, "extra")));
        }
        return out;
    }

    private List<Map<String, Object>> runExplainAsList(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            applyLimits(st);
            try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                // MySQL EXPLAIN 列名大小写混杂 (select_type, Extra 等), 这里统一 lowercase
                // 跟 ToolJson 整体 snake_case 风格对齐.
                String[] colNames = new String[cols + 1];
                for (int c = 1; c <= cols; c++) {
                    colNames[c] = md.getColumnLabel(c).toLowerCase(Locale.ROOT);
                }
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= cols; c++) {
                        Object v = rs.getObject(c);
                        row.put(colNames[c], v);
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static String colString(ResultSet rs, Map<String, Integer> idx, String col) throws SQLException {
        Integer i = idx.get(col);
        if (i == null) return null;
        return rs.getString(i);
    }

    private static Long colLongOrNull(ResultSet rs, Map<String, Integer> idx, String col) throws SQLException {
        Integer i = idx.get(col);
        if (i == null) return null;
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static String nz(String s) { return s == null ? "-" : s; }

    // ------------------------------------------------------------------
    // 内部 helpers
    // ------------------------------------------------------------------

    private String showCreateTable(Connection conn, String tableName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
            if (rs.next()) return rs.getString(2);
            return null;
        }
    }

    private long tableRowsEstimate(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_ROWS FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return -1;
            }
        }
    }

    /**
     * 从 information_schema.STATISTICS 聚合出每个索引的 (name, unique, columns, cardinality).
     * 复合索引 cardinality 取最大列(等于末列, 因为前缀 cardinality 单调递增) — 代表整索引 NDV.
     */
    private List<TableInfoResult.IndexEntry> indexEntries(Connection conn, String tableName) throws SQLException {
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        Map<String, Long> cardinality = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, CARDINALITY " +
                        "FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                        "ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idx = rs.getString(1);
                    boolean nonUnique = rs.getInt(2) == 1;
                    String col = rs.getString(3);
                    long card = rs.getLong(4);
                    boolean cardNull = rs.wasNull();
                    cols.computeIfAbsent(idx, k -> new ArrayList<>()).add(col);
                    unique.putIfAbsent(idx, !nonUnique);
                    if (!cardNull) {
                        cardinality.merge(idx, card, (a, b) -> b > a ? b : a);
                    } else {
                        cardinality.putIfAbsent(idx, null);
                    }
                }
            }
        }
        List<TableInfoResult.IndexEntry> out = new ArrayList<>();
        for (String idx : cols.keySet()) {
            out.add(new TableInfoResult.IndexEntry(
                    idx, unique.get(idx), List.copyOf(cols.get(idx)), cardinality.get(idx)));
        }
        return out;
    }

    private void applyLimits(Statement st) throws SQLException {
        st.setQueryTimeout(queryTimeoutSeconds);
        st.setMaxRows(maxRows);
        st.setFetchSize(Math.min(maxRows, 1000));
    }

    /**
     * 跑 SQL, 取前 VERIFY_TOP_N 行做规范化 + SHA-256 hash. Statement 自己限了 maxRows
     * 为安全兜底, 但 VERIFY_TOP_N 是 verify 真正用的截断点(更小, 性能更好).
     */
    private List<String> runAndHashRows(Connection conn, String sql) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            applyLimits(st);
            st.setMaxRows(VERIFY_TOP_N);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                int[] sqlTypes = new int[cols + 1];
                for (int c = 1; c <= cols; c++) {
                    sqlTypes[c] = md.getColumnType(c);
                }
                while (rs.next() && hashes.size() < VERIFY_TOP_N) {
                    hashes.add(hashRow(rs, cols, sqlTypes));
                }
            }
        }
        return hashes;
    }

    private static String hashRow(ResultSet rs, int cols, int[] sqlTypes) throws SQLException {
        StringBuilder raw = new StringBuilder();
        for (int c = 1; c <= cols; c++) {
            if (c > 1) raw.append(FIELD_SEP);
            Object v = rs.getObject(c);
            if (v == null) {
                raw.append(NULL_SENTINEL);
            } else {
                raw.append(normalize(v, sqlTypes[c]));
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(raw.toString().hashCode());
        }
    }

    /**
     * 按 JDBC 列类型规范化, 避免不同 driver / 不同精度 / padding 等差异导致同样数据产出不同 hash.
     */
    private static String normalize(Object v, int sqlType) {
        return switch (sqlType) {
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                if (v instanceof Timestamp ts) yield String.valueOf(ts.getTime());
                yield v.toString();
            }
            case Types.DATE -> {
                if (v instanceof Date d) yield String.valueOf(d.getTime());
                yield v.toString();
            }
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> {
                if (v instanceof Time t) yield String.valueOf(t.getTime());
                yield v.toString();
            }
            case Types.DECIMAL, Types.NUMERIC -> {
                if (v instanceof BigDecimal bd) yield bd.stripTrailingZeros().toPlainString();
                yield v.toString();
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                if (v instanceof Number n) {
                    yield String.format(Locale.ROOT, "%.6f", n.doubleValue());
                }
                yield v.toString();
            }
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> stripTrailingSpaces(v.toString());
            case Types.BOOLEAN, Types.BIT -> {
                if (v instanceof Boolean b) yield b ? "1" : "0";
                String s = v.toString();
                yield ("true".equalsIgnoreCase(s) || "1".equals(s)) ? "1" : "0";
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                if (v instanceof byte[] bytes) yield sha256HexOfBytes(bytes);
                yield v.toString();
            }
            default -> v.toString();
        };
    }

    /**
     * 只剥结尾 ASCII 空格(CHAR padding 用的是 0x20). 不剥 \n / \t / \r —
     * 数据列里的换行/制表符可能是真实业务字段(如导出过的备注), 剥掉会误判等价性.
     */
    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    private static String sha256HexOfBytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            return "bytes_len=" + bytes.length;
        }
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }
}
