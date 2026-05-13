package com.slowsql.agent.agent.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FactExtractorTest {

    private final FactExtractor extractor = new FactExtractor();

    @Test
    void extractsTableInfoSchemaFact() {
        String json = """
                {
                  "status":"ok","table":"orders",
                  "indexes":[
                    {"name":"PRIMARY","unique":true,"columns":["id"],"cardinality":12500000},
                    {"name":"idx_status_create","unique":false,"columns":["status","create_time"],"cardinality":2500}
                  ],
                  "estimated_rows":12500000
                }
                """;
        KeyFactStore store = new KeyFactStore();
        extractor.extract("getTableInfo", json, store);

        assertThat(store.size()).isEqualTo(1);
        String rendered = store.render();
        assertThat(rendered).contains("[schema]").contains("table=orders");
        assertThat(rendered).contains("pk=id");
        assertThat(rendered).contains("rows~12.5M");
        assertThat(rendered).contains("idx=");
        assertThat(rendered).contains("PRIMARY[U]");
        assertThat(rendered).contains("idx_status_create");
    }

    @Test
    void extractsExplainPlanFact() {
        String json = """
                {
                  "status":"ok",
                  "rows":[
                    {"id":1,"select_type":"SIMPLE","table":"orders","type":"range",
                     "key":"PRIMARY","rows":30,"extra":"Using where"}
                  ]
                }
                """;
        KeyFactStore store = new KeyFactStore();
        extractor.extract("runExplain", json, store);

        String rendered = store.render();
        assertThat(rendered).contains("[plan]").contains("last_explain");
        assertThat(rendered).contains("orders:range/PRIMARY");
        assertThat(rendered).contains("rows=30");
        assertThat(rendered).contains("Using where");
    }

    @Test
    void extractsVerifyResultFact() {
        String passJson = """
                {"status":"pass","strategy":"cursor_plan_validity",
                 "rows_reduction_pct":99.9876}
                """;
        KeyFactStore store = new KeyFactStore();
        extractor.extract("verifyResultEquivalence", passJson, store);

        String rendered = store.render();
        assertThat(rendered).contains("[verify]");
        assertThat(rendered).contains("PASS");
        assertThat(rendered).contains("cursor_plan_validity");
        assertThat(rendered).contains("99.99%");
    }

    @Test
    void extractsVerifyFailWithReason() {
        String failJson = """
                {"status":"fail","strategy":"cursor_plan_validity","reason":"missing_order_by"}
                """;
        KeyFactStore store = new KeyFactStore();
        extractor.extract("verifyResultEquivalence", failJson, store);

        String rendered = store.render();
        assertThat(rendered).contains("FAIL");
        assertThat(rendered).contains("missing_order_by");
    }

    @Test
    void unknownToolIsSilentlyIgnored() {
        KeyFactStore store = new KeyFactStore();
        extractor.extract("getColumnStats", "{\"status\":\"ok\"}", store);
        assertThat(store.size()).isZero();
    }

    @Test
    void malformedJsonDoesNotThrow() {
        KeyFactStore store = new KeyFactStore();
        extractor.extract("getTableInfo", "{not json", store);
        extractor.extract("runExplain", null, store);
        assertThat(store.size()).isZero();
    }

    @Test
    void errorStatusSkipsTableInfoButRecordsVerifyError() {
        KeyFactStore store = new KeyFactStore();
        // getTableInfo error 不抽事实
        extractor.extract("getTableInfo",
                "{\"status\":\"error\",\"reason\":\"not_found\"}", store);
        assertThat(store.size()).isZero();

        // verify error 仍然抽出"上次 verify 失败"这个事实, 让 LLM 知道发生过
        extractor.extract("verifyResultEquivalence",
                "{\"status\":\"error\",\"reason\":\"syntax_error\"}", store);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.render()).contains("ERROR").contains("syntax_error");
    }
}
