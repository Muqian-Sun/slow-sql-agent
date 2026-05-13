package com.slowsql.agent.tools;

import com.slowsql.agent.agent.memory.KeyFact;
import com.slowsql.agent.agent.memory.KeyFactStore;
import com.slowsql.agent.eval.AgentStatsListener;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单测 DiagnosisTools.recallFacts —— LLM 主动拉 fact 摘要的入口.
 * 用 MockToolBackend 即可, 不依赖 DB.
 */
class DiagnosisToolsRecallFactsTest {

    private DiagnosisTools build(KeyFactStore store) {
        return new DiagnosisTools(new MockToolBackend(), new AgentStatsListener(), store);
    }

    @Test
    void emptyStoreReturnsZeroCountWithEmptyArray() {
        DiagnosisTools tools = build(new KeyFactStore());
        String json = tools.recallFacts(null);
        assertThat(json).contains("\"status\":\"ok\"")
                .contains("\"total_count\":0")
                .contains("\"facts\":[]");
    }

    @Test
    void allCategoriesReturnedWhenFilterBlank() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id"));
        s.put(KeyFact.plan("last_explain", "orders:range/PRIMARY"));
        s.put(KeyFact.verify("last_verify", "PASS reduction=99%"));

        String json = build(s).recallFacts("");
        assertThat(json).contains("\"total_count\":3");
        assertThat(json).contains("table=orders");
        assertThat(json).contains("last_explain");
        assertThat(json).contains("last_verify");
    }

    @Test
    void filterBySchemaCategory() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id"));
        s.put(KeyFact.plan("last_explain", "orders:range/PRIMARY"));
        s.put(KeyFact.verify("last_verify", "PASS"));

        String json = build(s).recallFacts("schema");
        assertThat(json).contains("\"total_count\":1")
                .contains("\"category\":\"schema\"")
                .contains("table=orders")
                .doesNotContain("last_explain")
                .doesNotContain("last_verify");
    }

    @Test
    void filterByPlanCategory() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id"));
        s.put(KeyFact.plan("last_explain", "orders:range/PRIMARY"));

        String json = build(s).recallFacts("plan");
        assertThat(json).contains("\"total_count\":1")
                .contains("last_explain")
                .doesNotContain("table=orders");
    }

    @Test
    void unknownCategoryReturnsZero() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id"));

        String json = build(s).recallFacts("nonexistent");
        assertThat(json).contains("\"total_count\":0");
    }

    @Test
    void recallFactsBumpsStatsToolCallCount() {
        AgentStatsListener stats = new AgentStatsListener();
        DiagnosisTools tools = new DiagnosisTools(new MockToolBackend(), stats, new KeyFactStore());

        tools.recallFacts(null);
        tools.recallFacts("schema");

        assertThat(stats.totalToolCalls()).isEqualTo(2);
    }
}
