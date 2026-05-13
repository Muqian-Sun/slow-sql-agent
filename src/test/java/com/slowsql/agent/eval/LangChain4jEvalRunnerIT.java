package com.slowsql.agent.eval;

import com.slowsql.agent.agent.LangChain4jDiagnosisAgent;
import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.tools.DataSourceFactory;
import com.slowsql.agent.tools.JdbcConfig;
import com.slowsql.agent.tools.JdbcToolBackend;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端评测 — 真 LLM(MiMo / OpenAI-compat) + 真 MySQL + 全量黄金集.
 *
 * 仅在 SLOW_SQL_LLM_API_KEY + SLOW_SQL_DB_URL 同时注入时跑.
 * 默认本地 mvn test / CI 跳过, 避免烧 token + 长耗时.
 *
 * 触发命令:
 *   docker compose up -d mysql
 *   ( echo 'SET @scale := 1000000;'; cat samples/seed.sql ) | \
 *     docker exec -i slowsql-mysql mysql -uroot -proot slow_sql_agent
 *
 *   SLOW_SQL_LLM_BASE_URL=... SLOW_SQL_LLM_API_KEY=... \
 *   SLOW_SQL_LLM_MODEL=mimo-v2.5-pro \
 *   SLOW_SQL_LLM_EXTRA_BODY='{"thinking":{"type":"disabled"}}' \
 *   SLOW_SQL_DB_URL='jdbc:mysql://localhost:3307/slow_sql_agent?...' \
 *   SLOW_SQL_DB_USER=root SLOW_SQL_DB_PASSWORD=root \
 *   mvn -Dtest=LangChain4jEvalRunnerIT#fullEval test
 */
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LangChain4jEvalRunnerIT {

    private DataSource dataSource;
    private JdbcToolBackend toolBackend;
    private Supplier<LangChain4jDiagnosisAgent> agentFactory;

    @BeforeAll
    void setUp() {
        JdbcConfig dbConfig = JdbcConfig.fromEnv();
        this.dataSource = DataSourceFactory.build(dbConfig);
        this.toolBackend = new JdbcToolBackend(dataSource, dbConfig);
        LlmConfig llmConfig = LlmConfig.fromEnv();
        this.agentFactory = () -> new LangChain4jDiagnosisAgent(llmConfig, toolBackend);
    }

    @AfterAll
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    /**
     * 5 case smoke 评测 — 每种 outcome 各一个代表, ~2-3 分钟.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void smokeEval() throws Exception {
        EvalRunner runner = new EvalRunner(() -> agentFactory.get());
        EvalConfig config = new EvalConfig(
                Path.of("samples/golden_set.json"),
                GoldenSetLoader.SMOKE_CASE_IDS,
                1, "mimo-v2.5-pro-smoke", null, true,
                Path.of("target/eval-reports"));

        EvalReport report = runner.run(config);
        printSummary("SMOKE (5 cases × 1 iter)", report);

        assertThat(report.totalCases()).isEqualTo(5);
        assertThat(report.totalRuns()).isEqualTo(5);
    }

    /**
     * 全量 17 case 评测 — 1 iter, ~10-15 分钟.
     * 真要看采样波动可手工把 iterations 调到 3 跑半小时.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void fullEval() throws Exception {
        EvalRunner runner = new EvalRunner(() -> agentFactory.get());
        EvalConfig config = new EvalConfig(
                Path.of("samples/golden_set.json"),
                List.of(),
                1, "mimo-v2.5-pro-full", null, true,
                Path.of("target/eval-reports"));

        EvalReport report = runner.run(config);
        printSummary("FULL (17 cases × 1 iter)", report);

        assertThat(report.totalCases()).isEqualTo(17);
        assertThat(report.totalRuns()).isEqualTo(17);
    }

    private static void printSummary(String label, EvalReport report) {
        System.out.println("\n══════════════════════════════════════════════════════════════════");
        System.out.println("  " + label);
        System.out.println("══════════════════════════════════════════════════════════════════");
        System.out.printf("  cases:              %d%n", report.totalCases());
        System.out.printf("  runs:               %d%n", report.totalRuns());
        System.out.printf("  outcome_match:      %.1f%%%n", report.outcomeMatchRate() * 100);
        System.out.printf("  verify_pass:        %.1f%%%n", report.verificationPassRate() * 100);
        System.out.printf("  high_confidence:    %.1f%%%n", report.highConfidenceRate() * 100);
        System.out.printf("  assumptions_rate:   %.1f%%%n", report.assumptionsExplicitRate() * 100);
        System.out.printf("  avg_react_rounds:   %.2f%n", report.avgReactRounds());
        System.out.printf("  p95_latency:        %d ms%n", (long) report.p95LatencyMs());
        System.out.println("══════════════════════════════════════════════════════════════════\n");
    }
}
