package com.slowsql.agent.tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Hikari 连接池构造.
 *
 * 设计上保持极简:
 *   - 单一池, 默认 4 个连接, 仅服务工具层.
 *   - readOnly = true: Agent 工具任何查询都不应当落写, 哪怕 LLM 误推出 UPDATE 也被 driver 拒绝.
 *   - autoCommit = true: 工具调用都是无状态读, 不需要事务.
 */
public final class DataSourceFactory {

    private DataSourceFactory() {}

    public static DataSource build(JdbcConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.poolSize());
        hc.setMinimumIdle(1);
        hc.setReadOnly(true);
        hc.setAutoCommit(true);
        hc.setPoolName("slowsql-agent-pool");
        hc.setConnectionTimeout(5_000);
        hc.setValidationTimeout(3_000);
        return new HikariDataSource(hc);
    }
}
