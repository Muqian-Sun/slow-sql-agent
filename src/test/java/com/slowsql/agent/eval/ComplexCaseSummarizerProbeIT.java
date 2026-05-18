package com.slowsql.agent.eval;

import com.slowsql.agent.agent.LangChain4jDiagnosisAgent;
import com.slowsql.agent.agent.memory.LayeredChatMemory;
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

/**
 * 探针 IT — 只跑最复杂的几个 case 在 LAYERED 单路径, 不做 baseline 对照.
 * 用来观察 "长难 SQL" 上:
 *   - LayeredChatMemory.summarizerInvocations: cycle 压缩是否真的被触发?
 *   - reactRounds vs totalToolCalls: 若 LLM 启用 parallel tool calling 则 AiMessage 远少于工具调用,
 *     说明 compressAfterToolCalls 阈值要按"工具调用次数"换算 — 已按此粒度统一
 *   - case 是否被 tool_call_limit 截断, 截断之前堆了多少 ReAct 周期
 *
 * 这不是测试 — 是观察工具, 数据本身是结论.
 */
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLOW_SQL_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComplexCaseSummarizerProbeIT {

    private static final List<String> COMPLEX_CASE_IDS = List.of(
            "case_dp_dj_005",   // 6 表 JOIN
            "case_dp_dj_006",   // 5 表 JOIN + 非唯一 ORDER BY
            "case_dp_dj_008"    // complex
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
        if (dataSource instanceof HikariDataSource hds) hds.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void probe() throws Exception {
        EvalConfig config = new EvalConfig(
                Path.of("samples/golden_set.json"),
                COMPLEX_CASE_IDS,
                1,
                "summarizer-probe",
                "LayeredChatMemory(default)",
                false,
                Path.of("target/eval-reports"));

        EvalRunner runner = new EvalRunner(() -> new LangChain4jDiagnosisAgent(llmConfig, toolBackend));
        EvalReport report = runner.run(config);
        List<RunResult> runs = report.rawRuns();

        System.out.println("\n══════ Complex-case summarizer probe (compressAfterToolCalls="
                + LayeredChatMemory.DEFAULT_COMPRESS_AFTER_TOOL_CALLS + ") ══════");
        System.out.printf("%-22s %8s %10s %10s %10s %-40s %s%n",
                "case_id", "rounds", "tokens", "tool_calls", "summ_inv", "tool_breakdown", "outcome/error");
        System.out.println("--------------------------------------------------------------------------------");
        for (RunResult r : runs) {
            System.out.printf("%-22s %8d %10d %10d %10d %-40s %s%n",
                    r.caseId(),
                    r.reactRounds(),
                    r.totalTokens(),
                    r.totalToolCalls(),
                    r.summarizerInvocations(),
                    // toolFailureReasonsList 只暴露 failures; per-tool count 由 EvalRunner.runSingle 的 INFO log 输出.
                    // 这里用 toolFailuresByReason 给个简化视角 (空 map 表示 case 跑通无失败工具).
                    r.toolFailuresByReason() == null || r.toolFailuresByReason().isEmpty()
                            ? "(no tool failures)"
                            : r.toolFailuresByReason().toString(),
                    r.error() != null
                            ? "ERR: " + MemoryComparisonReport.classifyError(r.error())
                            : (r.diagnosis() != null ? r.diagnosis().outcome().name() : "(no diagnosis)"));
        }
        System.out.println("══════════════════════════════════════════════════════════════════");
        long anySummarized = runs.stream().filter(r -> r.summarizerInvocations() > 0).count();
        System.out.println("cases with summarizer triggered: " + anySummarized + "/" + runs.size());
        System.out.println("(per-tool call breakdown 见 EvalRunner INFO log: '  done case=... tools={...}')");
    }
}
