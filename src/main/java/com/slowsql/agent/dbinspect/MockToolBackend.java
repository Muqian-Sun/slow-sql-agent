package com.slowsql.agent.dbinspect;

import com.slowsql.agent.diagnosis.tools.result.ExplainResult;
import com.slowsql.agent.diagnosis.tools.result.TableInfoResult;
import com.slowsql.agent.diagnosis.tools.result.VerifyResult;
import com.slowsql.agent.diagnosis.tools.HintCatalog;
import com.slowsql.agent.diagnosis.tools.ErrorCategory;
import com.slowsql.agent.diagnosis.tools.SqlSafety;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线桩实现 — 仅供 LLM 主回路联调 / EvalRunner smoke 走通.
 *
 * 行为约定:
 *   - describeTable: 对已知表(orders / users / products)返回符合 samples/schema.sql 的简化 DDL.
 *   - explain:      对 LIMIT offset > 1000 的 SQL 返回 "type=index, rows=offset+limit" 暗示深分页.
 *   - verifyEquivalence: 默认 pass(general row_hash 形态), 让主回路走通; 真实接入见 JdbcToolBackend.
 *
 * 桩的 cardinality / rows 都是粗略占位, 不追求精确, 只保证 LLM 拿到合理形态.
 */
public class MockToolBackend implements ToolBackend {

    private final Map<String, String> tableDdl = new HashMap<>();
    private final Map<String, List<TableInfoResult.IndexEntry>> tableIndexes = new HashMap<>();
    private final Map<String, Long> tableRowsEst = new HashMap<>();

    public MockToolBackend() {
        registerOrders();
        registerUsers();
        registerProducts();
    }

    @Override
    public TableInfoResult describeTable(String tableName) {
        if (tableName == null) {
            return TableInfoResult.error("invalid_identifier", null);
        }
        String key = tableName.toLowerCase(Locale.ROOT);
        String ddl = tableDdl.get(key);
        if (ddl == null) {
            return TableInfoResult.error("not_found", tableName);
        }
        return TableInfoResult.ok(
                tableName, ddl,
                tableIndexes.getOrDefault(key, List.of()),
                tableRowsEst.getOrDefault(key, -1L));
    }

    @Override
    public ExplainResult explain(String sql) {
        if (sql == null || sql.isBlank()) {
            return ExplainResult.error("empty_sql", "sql is blank");
        }
        long offset = parseOffsetGuess(sql);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("select_type", "SIMPLE");
        row.put("table", "?");
        if (offset > 1000) {
            row.put("type", "index");
            row.put("key", "PRIMARY");
            row.put("rows", offset);
            row.put("Extra", "Using where; Using index");
        } else {
            row.put("type", "ref");
            row.put("key", "idx_user_id");
            row.put("rows", 50);
            row.put("Extra", "Using where");
        }
        return ExplainResult.ok(List.of(row));
    }

    @Override
    public VerifyResult verifyEquivalence(String originalSql, String rewrittenSql) {
        if (rewrittenSql == null || rewrittenSql.isBlank()) {
            return VerifyResult.error("rewritten_sql_unsafe", "rewritten_sql is empty");
        }
        // 桩简化: 一律 general row_hash PASS, 不感知改写形态; latency / speedup 留 null
        return VerifyResult.passRowHash("general", 20, null, null, 0L, null, null,
                null, null, null);
    }

    private long parseOffsetGuess(String sql) {
        try {
            String upper = sql.toUpperCase(Locale.ROOT);
            int idx = upper.lastIndexOf("LIMIT");
            if (idx < 0) return 0;
            String tail = sql.substring(idx + 5).trim();
            if (tail.contains(",")) {
                return Long.parseLong(tail.split(",")[0].trim());
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 桩数据 ----------

    private void registerOrders() {
        tableDdl.put("orders", """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    merchant_id BIGINT NOT NULL,
                    status TINYINT NOT NULL,
                    amount DECIMAL(10,2),
                    order_no VARCHAR(32),
                    create_time DATETIME NOT NULL,
                    pay_time DATETIME,
                    update_time DATETIME,
                    INDEX idx_user_id (user_id),
                    INDEX idx_merchant_id (merchant_id),
                    INDEX idx_create_time (create_time),
                    INDEX idx_status_create (status, create_time),
                    UNIQUE INDEX uk_order_no (order_no)
                )""");
        tableIndexes.put("orders", List.of(
                new TableInfoResult.IndexEntry("PRIMARY", true, List.of("id"), 10_000_000L),
                new TableInfoResult.IndexEntry("idx_user_id", false, List.of("user_id"), 100_000L),
                new TableInfoResult.IndexEntry("idx_merchant_id", false, List.of("merchant_id"), 10_000L),
                new TableInfoResult.IndexEntry("idx_create_time", false, List.of("create_time"), 10_000_000L),
                new TableInfoResult.IndexEntry("idx_status_create", false, List.of("status", "create_time"), 10_000_000L),
                new TableInfoResult.IndexEntry("uk_order_no", true, List.of("order_no"), 10_000_000L)));
        tableRowsEst.put("orders", 10_000_000L);
    }

    private void registerUsers() {
        tableDdl.put("users", """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    phone VARCHAR(20),
                    email VARCHAR(100),
                    name VARCHAR(50),
                    age INT,
                    gender TINYINT,
                    city VARCHAR(50),
                    status TINYINT DEFAULT 1,
                    register_time DATETIME NOT NULL,
                    last_login DATETIME,
                    INDEX idx_phone (phone),
                    INDEX idx_email (email),
                    INDEX idx_register_time (register_time)
                )""");
        tableIndexes.put("users", List.of(
                new TableInfoResult.IndexEntry("PRIMARY", true, List.of("id"), 5_000_000L),
                new TableInfoResult.IndexEntry("idx_phone", false, List.of("phone"), 5_000_000L),
                new TableInfoResult.IndexEntry("idx_email", false, List.of("email"), 5_000_000L),
                new TableInfoResult.IndexEntry("idx_register_time", false, List.of("register_time"), 5_000_000L)));
        tableRowsEst.put("users", 5_000_000L);
    }

    private void registerProducts() {
        tableDdl.put("products", """
                CREATE TABLE products (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    merchant_id BIGINT NOT NULL,
                    category_id BIGINT,
                    name VARCHAR(200) NOT NULL,
                    price DECIMAL(10,2),
                    stock INT,
                    status TINYINT DEFAULT 1,
                    create_time DATETIME NOT NULL,
                    INDEX idx_merchant (merchant_id),
                    INDEX idx_category (category_id),
                    INDEX idx_create_time (create_time)
                )""");
        tableIndexes.put("products", List.of(
                new TableInfoResult.IndexEntry("PRIMARY", true, List.of("id"), 1_000_000L),
                new TableInfoResult.IndexEntry("idx_merchant", false, List.of("merchant_id"), 10_000L),
                new TableInfoResult.IndexEntry("idx_category", false, List.of("category_id"), 100L),
                new TableInfoResult.IndexEntry("idx_create_time", false, List.of("create_time"), 1_000_000L)));
        tableRowsEst.put("products", 1_000_000L);
    }
}
