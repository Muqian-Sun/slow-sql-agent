package com.slowsql.agent.diagnosis.agent;

import com.slowsql.agent.diagnosis.api.BusinessContext;
import com.slowsql.agent.diagnosis.api.DiagnosisAgent;
import com.slowsql.agent.diagnosis.api.DiagnosisResult;
import com.slowsql.agent.diagnosis.api.OutcomeType;

import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.dbinspect.DataSourceFactory;
import com.slowsql.agent.dbinspect.JdbcConfig;
import com.slowsql.agent.dbinspect.JdbcToolBackend;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 端到端集成测试 — 真 LLM + 真 MySQL + 真双跑校验.
 *
 * 只在以下两个环境变量同时注入时跑(默认本地 mvn test / CI 跳过):
 *   SLOW_SQL_LLM_API_KEY   走 LangChain4j AiServices ReAct 主回路
 *   SLOW_SQL_DB_URL        通过 JdbcToolBackend 让四个工具落到真 MySQL
 *
 * 触发命令(配合 samples/seed.sql 灌好百万级数据):
 *   docker compose up -d mysql
 *   ( echo 'SET @scale := 1000000;'; cat samples/seed.sql ) | \
 *     docker exec -i slowsql-mysql mysql -uroot -proot slow_sql_agent
 *
 *   SLOW_SQL_LLM_BASE_URL=... SLOW_SQL_LLM_API_KEY=... SLOW_SQL_LLM_MODEL=mimo-v2.5-pro \
 *   SLOW_SQL_LLM_EXTRA_BODY='{"thinking":{"type":"disabled"}}' \
 *   SLOW_SQL_DB_URL='jdbc:mysql://localhost:3307/slow_sql_agent?...' \
 *   SLOW_SQL_DB_USER=root SLOW_SQL_DB_PASSWORD=root \
 *   mvn -Dtest=LangChain4jDiagnosisAgentIT test
 */
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LangChain4jDiagnosisAgentIT {

    private DataSource dataSource;
    private JdbcToolBackend toolBackend;
    private LlmConfig llmConfig;

    @BeforeAll
    void setUp() {
        JdbcConfig dbConfig = JdbcConfig.fromEnv();
        this.dataSource = DataSourceFactory.build(dbConfig);
        this.toolBackend = new JdbcToolBackend(dataSource, dbConfig);
        this.llmConfig = LlmConfig.fromEnv();
    }

    @AfterAll
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    /** 每个 case 都用全新 agent — AgentStatsListener 不跨 case 累加, 行为更接近 EvalRunner. */
    private LangChain4jDiagnosisAgent freshAgent() {
        return new LangChain4jDiagnosisAgent(llmConfig, toolBackend);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void deepPaginationOrdersListShouldRewriteAndVerify() {
        LangChain4jDiagnosisAgent agent = freshAgent();

        String sql = """
                SELECT id, user_id, amount, status, create_time
                FROM orders
                WHERE status = 1
                ORDER BY create_time DESC
                LIMIT 50000, 20
                """;
        BusinessContext ctx = BusinessContext.of(
                "B 端商家后台订单列表, 翻页常达数千页, 只能用传统分页 URL, 不能改前端 API");

        DiagnosisResult result = agent.diagnose(sql, ctx);

        printRunSummary("merchant_order_list (canModifyApi=false)", agent, result);

        // 结果合理性: 深分页应给出改写, 或归 unsupported(含索引 DDL 建议)
        assertThat(result.outcome()).isIn(
                OutcomeType.REWRITTEN_DEFERRED_JOIN,
                OutcomeType.REWRITTEN_CURSOR,
                OutcomeType.UNSUPPORTED);

        // 改写类结果必须给出 SQL
        if (result.outcome() == OutcomeType.REWRITTEN_DEFERRED_JOIN
                || result.outcome() == OutcomeType.REWRITTEN_CURSOR) {
            assertThat(result.rewrittenSql()).isNotBlank();
            // verifyResultEquivalence 在系统提示词里要求必调; 真接 JDBC 后, 工具调用次数应至少 1
            assertThat(agent.stats().totalToolCalls()).isGreaterThan(0);
        }

        // ReAct 至少进行了 1 轮
        assertThat(agent.stats().reactRounds()).isGreaterThan(0);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void deepPaginationFeedShouldFavorCursorWhenApiMutable() {
        LangChain4jDiagnosisAgent agent = freshAgent();

        // C 端信息流场景, 可改 API → 期望走游标分页路径
        String sql = """
                SELECT id, user_id, action_type, target_id, create_time
                FROM user_actions
                WHERE action_type = 'view'
                ORDER BY id DESC
                LIMIT 100000, 50
                """;
        BusinessContext ctx = BusinessContext.of(
                "C 端 APP 信息流, 无限下拉加载, 翻页深度可任意, 前端可以改成传 last_id 走游标分页");

        DiagnosisResult result = agent.diagnose(sql, ctx);

        printRunSummary("user_feed (canModifyApi=true)", agent, result);

        assertThat(result.outcome()).isIn(
                OutcomeType.REWRITTEN_CURSOR,
                OutcomeType.REWRITTEN_DEFERRED_JOIN,
                OutcomeType.UNSUPPORTED);
        if (result.outcome() == OutcomeType.REWRITTEN_CURSOR) {
            assertThat(result.rewrittenSql()).isNotBlank();
            // 游标分页改写必须依赖业务可改 API, assumptions 应显式提到
            assertThat(result.assumptions()).isNotEmpty();
        }
    }

    private static void printRunSummary(String label, LangChain4jDiagnosisAgent agent, DiagnosisResult result) {
        System.out.println("======================================");
        System.out.println("[E2E] " + label);
        System.out.println("  outcome:            " + result.outcome());
        System.out.println("  confidence:         " + result.confidence());
        System.out.println("  rewritten_sql:      " + truncate(result.rewrittenSql(), 280));
        System.out.println("  assumptions:        " + result.assumptions());
        System.out.println("  suggestions:        " + result.additionalSuggestions());
        System.out.println("  react_rounds:       " + agent.stats().reactRounds());
        System.out.println("  total_tool_calls:   " + agent.stats().totalToolCalls());
        System.out.println("  repeated_tool_calls:" + agent.stats().repeatedToolCalls());
        System.out.println("  failures_by_reason: " + agent.stats().failuresByReason());
        System.out.println("  total_tokens:       " + agent.stats().totalTokens());
        System.out.println("======================================");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
