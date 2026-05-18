package com.slowsql.agent.tools;

import com.slowsql.agent.agent.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具调用上限测试 — 验证超出 LIMIT_* 后抛 ToolCallLimitExceededException,
 * 而非返回 hint 让 LLM 继续选择. 与 LangChain4j maxSequentialToolsInvocations 一致的硬中断语义.
 *
 * 防回归点: 单 case 内 "verify_fail → 改写 → 又 fail" 死循环耗尽 30 轮的工具层兜底.
 */
class DiagnosisToolsCallLimitTest {

    private DiagnosisTools tools() {
        return new DiagnosisTools(new MockToolBackend(), new AgentStatsListener(), new KeyFactStore());
    }

    @Test
    void verifyThrowsOnFourthCall() {
        DiagnosisTools t = tools();
        // 前 LIMIT_VERIFY 次正常执行 (返回字符串)
        for (int i = 0; i < DiagnosisTools.LIMIT_VERIFY; i++) {
            assertThat(t.verifyResultEquivalence("SELECT 1", "SELECT 1")).isNotEmpty();
        }
        // 第 LIMIT_VERIFY+1 次直接抛
        assertThatThrownBy(() -> t.verifyResultEquivalence("SELECT 1", "SELECT 1"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("verifyResultEquivalence")
                .hasMessageContaining(String.valueOf(DiagnosisTools.LIMIT_VERIFY));
    }

    @Test
    void getTableInfoThrowsAfterLimit() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_GET_TABLE_INFO; i++) {
            assertThat(t.getTableInfo("orders")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.getTableInfo("orders"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("getTableInfo");
    }

    @Test
    void runExplainThrowsAfterLimit() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_RUN_EXPLAIN; i++) {
            assertThat(t.runExplain("SELECT 1")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.runExplain("SELECT 1"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("runExplain");
    }

    @Test
    void recallFactsThrowsAfterLimit() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_RECALL_FACTS; i++) {
            assertThat(t.recallFacts("")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.recallFacts(""))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("recallFacts");
    }

    @Test
    void exceptionCarriesToolNameAndLimit() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_VERIFY; i++) {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        }
        try {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        } catch (ToolCallLimitExceededException e) {
            assertThat(e.toolName()).isEqualTo("verifyResultEquivalence");
            assertThat(e.limit()).isEqualTo(DiagnosisTools.LIMIT_VERIFY);
            return;
        }
        throw new AssertionError("expected ToolCallLimitExceededException not thrown");
    }

    @Test
    void limitsAreIndependentPerTool() {
        DiagnosisTools t = tools();
        // 把 verify 打到 3 次 (上限内) — 不应影响 getTableInfo 的余量
        for (int i = 0; i < DiagnosisTools.LIMIT_VERIFY; i++) {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        }
        assertThat(t.getTableInfo("orders")).isNotEmpty();
    }
}
