package com.slowsql.agent.dbinspect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单测 JdbcToolBackend 私有 SQL 解析 helper (extractMainTable / extractOrderByColumns).
 * 走 reflection 是为了不暴露这些 helper 到 public API. 逻辑纯函数, 不依赖 DB.
 */
class OrderByUniquenessParseTest {

    @Test
    void extractMainTableFromSimpleSelect() throws Exception {
        assertThat(invokeExtractMainTable("SELECT * FROM orders WHERE id = 1"))
                .isEqualTo("orders");
        assertThat(invokeExtractMainTable("SELECT o.id FROM `orders` o JOIN users u ON o.user_id = u.id"))
                .isEqualTo("orders");
        assertThat(invokeExtractMainTable("select id from orders o where status = 1"))
                .isEqualTo("orders");
    }

    @Test
    void extractMainTableReturnsNullOnMalformed() throws Exception {
        assertThat(invokeExtractMainTable("")).isNull();
        assertThat(invokeExtractMainTable("SELECT 1")).isNull();
        assertThat(invokeExtractMainTable(null)).isNull();
    }

    @Test
    void extractOrderByColumnsNormalizesAliasAndDirection() throws Exception {
        // 别名前缀去掉, ASC/DESC 去掉, 反引号去掉, 小写
        assertThat(invokeExtractOrderByColumns(
                "SELECT * FROM orders o ORDER BY o.create_time DESC, o.`id` ASC LIMIT 100"))
                .containsExactlyInAnyOrder("create_time", "id");
    }

    @Test
    void extractOrderByColumnsBeforeLimit() throws Exception {
        assertThat(invokeExtractOrderByColumns(
                "SELECT id FROM orders ORDER BY create_time LIMIT 50000, 20"))
                .containsExactlyInAnyOrder("create_time");
    }

    @Test
    void extractOrderByColumnsHandlesNoOrderBy() throws Exception {
        assertThat(invokeExtractOrderByColumns("SELECT * FROM orders LIMIT 10")).isEmpty();
    }

    @Test
    void extractOrderByColumnsSkipsFunctionExpressions() throws Exception {
        // 函数列 (DATE(create_time)) 跳过 — 视为不可识别列名, 让 uniqueness 判断走保守的"非唯一"分支
        assertThat(invokeExtractOrderByColumns(
                "SELECT * FROM orders ORDER BY DATE(create_time), id"))
                .containsExactlyInAnyOrder("id");
    }

    @Test
    void extractOrderByColumnsHandlesMultipleColumnsWithMixedDirection() throws Exception {
        assertThat(invokeExtractOrderByColumns(
                "SELECT * FROM orders o ORDER BY o.status, o.create_time DESC, o.id ASC LIMIT 20"))
                .containsExactlyInAnyOrder("status", "create_time", "id");
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String invokeExtractMainTable(String sql) throws Exception {
        Method m = JdbcToolBackend.class.getDeclaredMethod("extractMainTable", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, sql);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> invokeExtractOrderByColumns(String sql) throws Exception {
        Method m = JdbcToolBackend.class.getDeclaredMethod("extractOrderByColumns", String.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(null, sql);
    }
}
