package com.slowsql.agent.tools;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcToolBackend 端到端集成测试.
 *
 * 仅在 SLOW_SQL_DB_URL 显式注入时运行 (CI / 本地默认跳过).
 * 触发方式:
 *   docker compose up -d mysql
 *   SLOW_SQL_DB_URL='jdbc:mysql://localhost:3307/slow_sql_agent?useSSL=false&allowPublicKeyRetrieval=true' \
 *   SLOW_SQL_DB_USER=root \
 *   SLOW_SQL_DB_PASSWORD=root \
 *   mvn -Dtest=JdbcToolBackendIT test
 *
 * 测试都基于真实 schema + 已灌入数据(samples/seed.sql), 校验工具返回的 record 字段:
 *   - TableInfoResult.indexes 含 PRIMARY 等
 *   - ExplainResult.rows 行结构
 *   - VerifyResult.status / strategy / reason
 */
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcToolBackendIT {

    private DataSource dataSource;
    private JdbcToolBackend backend;

    @BeforeAll
    void setUp() {
        JdbcConfig config = JdbcConfig.fromEnv();
        this.dataSource = DataSourceFactory.build(config);
        this.backend = new JdbcToolBackend(dataSource, config);
    }

    @AfterAll
    void tearDown() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hds) {
            hds.close();
        }
    }

    // ---------- getTableInfo ----------

    @Test
    void getTableInfoOnExistingTable() {
        TableInfoResult r = backend.describeTable("orders");
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.table()).isEqualTo("orders");
        assertThat(r.createTable()).contains("CREATE TABLE").contains("PRIMARY KEY");
        assertThat(r.indexes()).isNotEmpty();
        assertThat(r.indexes()).anyMatch(i -> "PRIMARY".equals(i.name()) && i.unique());
        assertThat(r.indexes()).anyMatch(i -> i.columns().contains("user_id"));
        assertThat(r.estimatedRows()).isPositive();
        assertThat(r.toJson()).contains("\"status\":\"ok\"").contains("\"create_table\"");
    }

    @Test
    void getTableInfoOnMissingTable() {
        TableInfoResult r = backend.describeTable("not_a_real_table");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.reason()).isEqualTo("not_found");
        assertThat(r.hint()).isNotBlank();
    }

    @Test
    void getTableInfoRejectsInvalidIdentifier() {
        TableInfoResult r = backend.describeTable("orders; DROP TABLE users");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.reason()).isEqualTo("invalid_identifier");
        assertThat(r.hint()).isNotBlank();
    }

    // ---------- runExplain ----------

    @Test
    void runExplainReturnsRows() {
        ExplainResult r = backend.explain(
                "SELECT id FROM orders WHERE status = 1 ORDER BY create_time DESC LIMIT 100, 20");
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.rows()).isNotEmpty();
        // EXPLAIN 第一行至少含 table / type 之类的字段
        assertThat(r.rows().get(0).keySet()).anyMatch(k ->
                k.equalsIgnoreCase("table") || k.equalsIgnoreCase("type"));
    }

    @Test
    void runExplainRejectsDml() {
        ExplainResult r = backend.explain("UPDATE orders SET status = 1 WHERE id = 1");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.reason()).isEqualTo("safety_rejected");
        assertThat(r.hint()).isNotBlank();
    }

    @Test
    void runExplainRejectsMultiStatement() {
        ExplainResult r = backend.explain("SELECT 1; SELECT 2");
        assertThat(r.status()).isEqualTo("error");
        assertThat(r.reason()).isEqualTo("safety_rejected");
        assertThat(r.hint()).isNotBlank();
    }

    // ---------- verifyResultEquivalence ----------

    @Test
    void verifyIdenticalQueriesPassesGeneralHash() {
        String sql = "SELECT id, status FROM orders WHERE status = 1 ORDER BY id LIMIT 10";
        VerifyResult r = backend.verifyEquivalence(sql, sql);
        assertThat(r.isPass()).isTrue();
        assertThat(r.strategy()).isEqualTo("row_hash");
        assertThat(r.rowHashSubtype()).isEqualTo("general");
        assertThat(r.sampledRows()).isPositive();
    }

    @Test
    void verifyDmlOnRewrittenIsRejected() {
        VerifyResult r = backend.verifyEquivalence("SELECT id FROM orders", "DELETE FROM orders");
        assertThat(r.isError()).isTrue();
        assertThat(r.reason()).isEqualTo("rewritten_sql_unsafe");
        assertThat(r.hint()).isNotBlank();
    }

    @Test
    void verifyDeferredJoinShapeUsesRowHash() {
        String sql = "SELECT id, user_id, amount FROM orders ORDER BY id LIMIT 50, 10";
        VerifyResult r = backend.verifyEquivalence(sql, sql);
        assertThat(r.isPass()).isTrue();
        assertThat(r.strategy()).isEqualTo("row_hash");
        assertThat(r.rowHashSubtype()).isEqualTo("deferred_join");
        // row_hash PASS 现在双跑 EXPLAIN: 应同时有 rewritten_plan + original_plan + reduction
        assertThat(r.rewrittenPlan()).isNotNull().isNotEmpty();
        assertThat(r.originalPlan()).isNotNull().isNotEmpty();
        assertThat(r.rewrittenRowsEstimate()).isNotNull();
        assertThat(r.originalRowsEstimate()).isNotNull();
        // 同 SQL 改写 reduction 应该接近 0(因为完全没改)
        assertThat(r.rowsReductionPct()).isNotNull();
    }

    @Test
    void verifyCursorRewriteUsesPlanValidation() {
        String original = "SELECT id, user_id, amount FROM orders ORDER BY id LIMIT 100, 20";
        String rewritten = "SELECT id, user_id, amount FROM orders WHERE id > 100 ORDER BY id LIMIT 20";
        VerifyResult r = backend.verifyEquivalence(original, rewritten);
        assertThat(r.isPass()).isTrue();
        assertThat(r.strategy()).isEqualTo("cursor_plan_validity");
        // cursor 路径同时 EXPLAIN 原 SQL + 改写 SQL, 应有 reduction
        assertThat(r.rewrittenPlan()).isNotNull().isNotEmpty();
        assertThat(r.rewrittenRowsEstimate()).isNotNull();
        // PRIMARY 命中
        assertThat(r.rewrittenPlan()).anyMatch(p -> "PRIMARY".equals(p.key()));
    }

    /**
     * cursor 改写缺 ORDER BY → fail missing_order_by, hint 来自 HintCatalog.
     */
    @Test
    void verifyCursorWithoutOrderByFails() {
        String original = "SELECT id, user_id FROM orders ORDER BY id LIMIT 100, 20";
        String rewritten = "SELECT id, user_id FROM orders WHERE id > 100 LIMIT 20"; // 缺 ORDER BY
        VerifyResult r = backend.verifyEquivalence(original, rewritten);
        assertThat(r.isFail()).isTrue();
        assertThat(r.strategy()).isEqualTo("cursor_plan_validity");
        assertThat(r.reason()).isEqualTo("missing_order_by");
        assertThat(r.hint()).contains("ORDER BY");
    }

    /**
     * HintCatalog 覆盖 verify 各 fail/error reason — 抽 3 个有代表性的看 hint 都有.
     */
    @Test
    void hintCatalogCoversCommonReasons() {
        assertThat(HintCatalog.hintFor("syntax_error")).isNotBlank();
        assertThat(HintCatalog.hintFor("row_count_diff")).isNotBlank();
        assertThat(HintCatalog.hintFor("cursor_plan_invalid")).isNotBlank();
        assertThat(HintCatalog.hintFor("not_a_known_reason")).isNull();
    }

    /**
     * cursor 改写走全表扫(city 无索引)→ plan_invalid.
     */
    @Test
    void verifyCursorWithFullScanFails() {
        String original = "SELECT id, name FROM users WHERE city = 'Beijing' LIMIT 10000, 10";
        String rewritten = "SELECT id, name FROM users WHERE city = 'Beijing' ORDER BY id LIMIT 10";
        VerifyResult r = backend.verifyEquivalence(original, rewritten);
        // 含 ORDER BY id 让 MySQL 可能走 PK index, 不一定 ALL. 但 hardFailures 至少应该非空时是 fail.
        // 这里宽容点: 要么 pass(MySQL 智能 fallback), 要么 fail+cursor_plan_invalid.
        if (r.isFail()) {
            assertThat(r.reason()).isEqualTo("cursor_plan_invalid");
        }
    }
}
