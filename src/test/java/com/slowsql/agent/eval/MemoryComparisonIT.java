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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 降幅对照实验 — 真 LLM + 真 MySQL, 跑相同 case 集两遍:
 *   1) LayeredChatMemory (生产路径, 含 LlmHistorySummarizer)
 *   2) MessageWindowChatMemory(maxMessages=10) — LangChain4j 默认滑窗 baseline
 *
 * 输出 target/eval-reports/memory-comparison-*.json 与控制台对照表;
 * "token 占用较默认滑窗的相对降幅" 这一对外结论由本 IT 的产出佐证.
 *
 * 仅在 SLOW_SQL_LLM_API_KEY + SLOW_SQL_DB_URL 同时注入时启用. 一次完整运行
 * (6 case × 1 iter × 2 variant ≈ 6-8 分钟) — 不放 CI, 需 baseline 时手工触发.
 *
 * 触发:
 *   SLOW_SQL_LLM_BASE_URL=... SLOW_SQL_LLM_API_KEY=... SLOW_SQL_LLM_MODEL=... \
 *   SLOW_SQL_DB_URL=... SLOW_SQL_DB_USER=... SLOW_SQL_DB_PASSWORD=... \
 *   mvn -Dtest=MemoryComparisonIT#compare test
 */
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryComparisonIT {

    /**
     * 对照 case 子集 — 覆盖三种 outcome + 一个高轮次 case (dj_006),
     * 保证 layered 的摘要器路径在实验里被真触发, 而不是空跑.
     *
     * 注: 不放 dj_005 (6 表 JOIN). 实测 MiMo 在 6 表 prompt 上单 case 跑 10+ 分钟,
     *     双变体跑会破 30 min 超时. dj_006 已经是复杂高轮次代表, 不重复.
     */
    private static final List<String> COMPARISON_CASE_IDS = List.of(
            "case_dp_dj_001",   // 简单单表 deferred_join
            "case_dp_dj_006",   // 高轮次设计 - 5 表 JOIN + 非唯一排序键 (唯一能真触发摘要器的 case)
            "case_dp_cur_001",  // 简单 cursor
            "case_dp_idx_001",  // unsupported - 缺索引
            "case_dp_oos_004"   // unsupported - DML
    );

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

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    void compare() throws Exception {
        EvalConfig config = new EvalConfig(
                Path.of("samples/golden_set.json"),
                COMPARISON_CASE_IDS,
                3,                                  // 3 iter 取均值, 用 token stdDev 衡量 LLM 采样方差;
                                                    // N=1 抖动会让 reduction 数字不可信 (实测 dj_006
                                                    // 撞 30 上限 vs 10 轮跑完都见过, token 能差 3 倍)
                "mimo-comparison",
                "MessageWindowChatMemory(10)",
                true,
                Path.of("target/eval-reports"));

        MemoryComparisonRunner runner = new MemoryComparisonRunner(
                () -> new LangChain4jDiagnosisAgent(llmConfig, toolBackend),
                () -> LangChain4jDiagnosisAgent.withBaselineMemory(llmConfig, toolBackend));

        MemoryComparisonReport report = runner.run(config);
        System.out.println(report.renderConsoleTable());

        assertThat(report.casesCompared()).isGreaterThan(0);
        // 不强断言 reduction > 某阈值 — 是否真有降幅是实验结论, 不是 IT 通过条件.
        // 如果 baseline 比 layered 还省, 那是个有意义的发现, 不该让 build 红.
    }
}
