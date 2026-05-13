package com.slowsql.agent.agent.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyFactStoreTest {

    @Test
    void emptyStoreRendersEmptyString() {
        assertThat(new KeyFactStore().render()).isEmpty();
    }

    @Test
    void putGroupsByCategoryAndPreservesOrder() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id rows~12.5M"));
        s.put(KeyFact.plan("last_explain", "orders:range/PRIMARY,rows=30"));
        s.put(KeyFact.verify("last_verify", "PASS (cursor_plan_validity)"));

        String rendered = s.render();
        assertThat(rendered).contains("=== 已确认事实");
        assertThat(rendered).contains("[schema]");
        assertThat(rendered).contains("[plan]");
        assertThat(rendered).contains("[verify]");
        // 顺序: schema 在前(先插入), plan 中, verify 末尾
        assertThat(rendered.indexOf("[schema]"))
                .isLessThan(rendered.indexOf("[plan]"))
                .isLessThan(rendered.indexOf("[verify]"));
    }

    @Test
    void sameCategorySubjectIsReplaced() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id rows~10M"));
        s.put(KeyFact.schema("table=orders", "pk=id rows~12.5M"));  // 更新

        assertThat(s.size()).isEqualTo(1);
        assertThat(s.render()).contains("rows~12.5M").doesNotContain("rows~10M");
    }

    @Test
    void differentSubjectsCoexist() {
        KeyFactStore s = new KeyFactStore();
        s.put(KeyFact.schema("table=orders", "pk=id"));
        s.put(KeyFact.schema("table=users", "pk=id"));
        assertThat(s.size()).isEqualTo(2);
    }

    @Test
    void exceedingCapacityEvictsOldest() {
        KeyFactStore s = new KeyFactStore();
        for (int i = 0; i < 20; i++) {  // CAPACITY=16
            s.put(KeyFact.schema("table=t" + i, "row " + i));
        }
        assertThat(s.size()).isEqualTo(16);
        // t0..t3 应该被 evict 掉, 最新的 t4..t19 保留
        assertThat(s.render()).doesNotContain("table=t0,").doesNotContain("table=t3,");
        assertThat(s.render()).contains("table=t19");
    }

    @Test
    void putNullIsNoop() {
        KeyFactStore s = new KeyFactStore();
        s.put(null);
        assertThat(s.size()).isZero();
    }
}
