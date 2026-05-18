package com.slowsql.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HintCatalog reason → ErrorCategory 映射锁定 — 防归类被误改、防新增 reason 漏写映射.
 */
class HintCatalogTest {

    @Test
    void queryTimeoutMapsToDedicatedCategoryNotInternal() {
        // 关键回归点: timeout 不能跌进 INTERNAL, 否则 LLM 拿到的 hint 是"跳过本次工具调用",
        // 对深分页超时这种"SQL 真慢"信号是错的指引.
        assertThat(HintCatalog.categoryOf("query_timeout"))
                .isEqualTo(ErrorCategory.QUERY_TIMEOUT)
                .isNotEqualTo(ErrorCategory.INTERNAL);
        assertThat(HintCatalog.hintFor("query_timeout"))
                .contains("查询超时")
                .doesNotContain("跳过这次工具调用");
    }

    @Test
    void knownReasonsAllMapped() {
        for (String reason : new String[]{
                "not_found", "invalid_identifier",
                "safety_rejected", "empty_sql",
                "original_sql_unsafe", "rewritten_sql_unsafe",
                "syntax_error", "explain_returned_empty",
                "row_count_diff", "content_mismatch",
                "order_mismatch", "missing_order_by",
                "cursor_plan_invalid",
                "query_timeout",
                "internal_error", "json_serialize_fail"}) {
            assertThat(HintCatalog.categoryOf(reason))
                    .as("reason=%s 应有 category 映射", reason)
                    .isNotNull();
            assertThat(HintCatalog.hintFor(reason))
                    .as("reason=%s 应有 hint", reason)
                    .isNotBlank();
        }
    }

    @Test
    void unknownReasonReturnsNull() {
        assertThat(HintCatalog.categoryOf("totally_made_up_reason")).isNull();
        assertThat(HintCatalog.hintFor("totally_made_up_reason")).isNull();
        assertThat(HintCatalog.categoryOf(null)).isNull();
    }
}
