package com.slowsql.agent.diagnosis.tools.result;

import com.slowsql.agent.diagnosis.memory.KeyFactStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口契约: 每个 Result record 知道怎么把自己抽成 KeyFact.
 *
 * 之前的 FactExtractor 用 hard-coded JSON 字段解析 — 字段重命名 / 新加工具时静默丢 fact.
 * 现在 record 直接实现 FactExportable.exportFactsTo(store), 字段访问编译期检查,
 * 字段重命名直接编译失败.
 */
class FactExportableTest {

    // -------- TableInfoResult --------

    @Test
    void tableInfoExportsSchemaFactWithPkAndIndexes() {
        TableInfoResult.IndexEntry pk = new TableInfoResult.IndexEntry(
                "PRIMARY", true, List.of("id"), 1_000_000L);
        TableInfoResult.IndexEntry idx = new TableInfoResult.IndexEntry(
                "idx_status_create", false, List.of("status", "create_time"), 500_000L);
        TableInfoResult r = TableInfoResult.ok("orders", "CREATE TABLE orders (...)",
                List.of(pk, idx), 1_000_000L);

        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);
        assertThat(store.size()).isEqualTo(1);
        String rendered = store.render();
        assertThat(rendered).contains("table=orders");
        assertThat(rendered).contains("pk=id");
        assertThat(rendered).contains("rows~1.0M");
        assertThat(rendered).contains("idx_status_create");
    }

    @Test
    void tableInfoErrorDoesNotExportFact() {
        TableInfoResult r = TableInfoResult.error("not_found", "missing_table");
        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);
        assertThat(store.size()).isZero();
    }

    // -------- ExplainResult --------

    @Test
    void explainExportsPlanFactCombiningAllRows() {
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("table", "orders"); row1.put("type", "range"); row1.put("key", "idx_status_create");
        row1.put("rows", 1500L); row1.put("Extra", "Using where");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("table", "users"); row2.put("type", "eq_ref"); row2.put("key", "PRIMARY");
        row2.put("rows", 1L); row2.put("Extra", "");

        ExplainResult r = ExplainResult.ok(List.of(row1, row2));
        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);

        assertThat(store.size()).isEqualTo(1);
        String rendered = store.render();
        assertThat(rendered).contains("last_explain");
        assertThat(rendered).contains("orders:range/idx_status_create");
        assertThat(rendered).contains("users:eq_ref/PRIMARY");
        assertThat(rendered).contains("total_rows=1.5K");
    }

    @Test
    void explainErrorDoesNotExportFact() {
        ExplainResult r = ExplainResult.error("syntax_error", "near 'FROOM'");
        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);
        assertThat(store.size()).isZero();
    }

    // -------- VerifyResult --------

    @Test
    void verifyPassExportsVerifyFactWithReduction() {
        VerifyResult r = VerifyResult.passRowHash("deferred_join", 100, List.of(), List.of(),
                20L, 1000L, 98.0, 50L, 5L, 10.0);
        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);

        assertThat(store.size()).isEqualTo(1);
        String rendered = store.render();
        assertThat(rendered).contains("last_verify");
        assertThat(rendered).contains("PASS");
        assertThat(rendered).contains("reduction=98.00%");
    }

    @Test
    void verifyFailExportsVerifyFactWithReason() {
        VerifyResult r = VerifyResult.failRowHash("content_mismatch", "deferred_join",
                100, 7, 3, "rows=100, first_diff_at_index=7", 50L, 45L, 1.1);
        KeyFactStore store = new KeyFactStore();
        r.exportFactsTo(store);

        assertThat(store.size()).isEqualTo(1);
        String rendered = store.render();
        assertThat(rendered).contains("FAIL");
        assertThat(rendered).contains("reason=content_mismatch");
    }
}
