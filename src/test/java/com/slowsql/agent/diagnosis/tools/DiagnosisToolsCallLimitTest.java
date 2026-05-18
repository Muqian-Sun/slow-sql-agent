package com.slowsql.agent.diagnosis.tools;

import com.slowsql.agent.dbinspect.MockToolBackend;

import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具调用上限测试 — 上限语义: 同工具 + 同参数 累计 > LIMIT_PER_TOOL_PARAM (3) 抛
 * ToolCallLimitExceededException. 不同参数算独立调用, 不累计.
 *
 * 防回归点:
 *   1. 同 SQL 反复调 verify ≥ 4 次 → 抛 (真死循环)
 *   2. 不同 SQL 各调 verify 1 次 N 次 → 不抛 (合法的探索新方向)
 *   3. 同工具不同参数互不干扰
 */
class DiagnosisToolsCallLimitTest {

    private DiagnosisTools tools() {
        return new DiagnosisTools(new MockToolBackend(), new AgentStatsListener(), new KeyFactStore());
    }

    @Test
    void verifyThrowsOnFourthSameSqlCall() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            assertThat(t.verifyResultEquivalence("SELECT 1", "SELECT 1")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.verifyResultEquivalence("SELECT 1", "SELECT 1"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("verifyResultEquivalence")
                .hasMessageContaining(String.valueOf(DiagnosisTools.LIMIT_PER_TOOL_PARAM));
    }

    @Test
    void verifyDoesNotThrowOnDifferentRewrittenSqlsEvenWhenManyCalls() {
        DiagnosisTools t = tools();
        // 10 个不同的改写 SQL 各调一次 verify → 不应撞墙, 因为每个参数独立计数
        for (int i = 0; i < 10; i++) {
            assertThat(t.verifyResultEquivalence(
                    "SELECT * FROM orders WHERE id < " + i,
                    "SELECT * FROM orders WHERE id < " + i + " /*v" + i + "*/")).isNotEmpty();
        }
    }

    @Test
    void getTableInfoThrowsAfterFourthSameTableCall() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            assertThat(t.getTableInfo("orders")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.getTableInfo("orders"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("getTableInfo");
    }

    @Test
    void getTableInfoAllowsManyDistinctTables() {
        DiagnosisTools t = tools();
        // 7 张不同的表各调一次 — 不撞墙
        for (String tab : new String[]{"orders", "users", "products", "merchants",
                                       "order_items", "categories", "reviews"}) {
            assertThat(t.getTableInfo(tab)).isNotEmpty();
        }
    }

    @Test
    void runExplainThrowsAfterFourthSameSqlCall() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            assertThat(t.runExplain("SELECT 1")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.runExplain("SELECT 1"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("runExplain");
    }

    @Test
    void recallFactsThrowsAfterFourthSameCategoryCall() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            assertThat(t.recallFacts("")).isNotEmpty();
        }
        assertThatThrownBy(() -> t.recallFacts(""))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("recallFacts");
    }

    @Test
    void exceptionCarriesToolNameAndLimit() {
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        }
        try {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        } catch (ToolCallLimitExceededException e) {
            assertThat(e.toolName()).isEqualTo("verifyResultEquivalence");
            assertThat(e.limit()).isEqualTo(DiagnosisTools.LIMIT_PER_TOOL_PARAM);
            return;
        }
        throw new AssertionError("expected ToolCallLimitExceededException not thrown");
    }

    @Test
    void verifyHasGlobalCapAcrossDistinctRewrites() {
        // verify 独享的全局上限: 不论参数, 累计 > LIMIT_VERIFY_TOTAL 抛.
        // 防"无限写不同改写"失控 — 其他工具不需要这种兜底.
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_VERIFY_TOTAL; i++) {
            // 每次都用不同 SQL, per-args 上限拦不住
            assertThat(t.verifyResultEquivalence(
                    "SELECT 1", "SELECT 1 /*v" + i + "*/")).isNotEmpty();
        }
        // 第 LIMIT_VERIFY_TOTAL+1 次抛
        assertThatThrownBy(() -> t.verifyResultEquivalence("SELECT 1", "SELECT 1 /*overflow*/"))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .hasMessageContaining("verifyResultEquivalence")
                .hasMessageContaining(String.valueOf(DiagnosisTools.LIMIT_VERIFY_TOTAL));
    }

    @Test
    void otherToolsNotAffectedByVerifyTotalLimit() {
        // verify 累计上限不影响其他工具的配额
        DiagnosisTools t = tools();
        for (int i = 0; i < DiagnosisTools.LIMIT_VERIFY_TOTAL; i++) {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1 /*v" + i + "*/");
        }
        // verify 已到全局上限, getTableInfo / runExplain / recallFacts 仍能正常工作
        assertThat(t.getTableInfo("orders")).isNotEmpty();
        assertThat(t.runExplain("SELECT * FROM orders LIMIT 10")).isNotEmpty();
        assertThat(t.recallFacts("")).isNotEmpty();
    }

    @Test
    void sameToolDifferentArgsDoNotShareQuota() {
        DiagnosisTools t = tools();
        // 把 verify(SELECT 1, SELECT 1) 打到 3 次 (同参数上限内)
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            t.verifyResultEquivalence("SELECT 1", "SELECT 1");
        }
        // 不同参数依然有完整 3 次配额
        for (int i = 0; i < DiagnosisTools.LIMIT_PER_TOOL_PARAM; i++) {
            assertThat(t.verifyResultEquivalence("SELECT 2", "SELECT 2")).isNotEmpty();
        }
        // SELECT 1 第 4 次仍然抛
        assertThatThrownBy(() -> t.verifyResultEquivalence("SELECT 1", "SELECT 1"))
                .isInstanceOf(ToolCallLimitExceededException.class);
    }
}
