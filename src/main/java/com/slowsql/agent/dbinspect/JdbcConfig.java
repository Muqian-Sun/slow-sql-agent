package com.slowsql.agent.dbinspect;

import java.time.Duration;

/**
 * JDBC 数据源配置 — 仅服务于 Agent 工具层, 不暴露给主应用其它代码.
 *
 * 从环境变量加载:
 *   - SLOW_SQL_DB_URL              jdbc:mysql://localhost:3307/slow_sql_agent
 *   - SLOW_SQL_DB_USER             默认 root
 *   - SLOW_SQL_DB_PASSWORD         默认 root
 *   - SLOW_SQL_DB_POOL_SIZE        默认 4
 *   - SLOW_SQL_DB_QUERY_TIMEOUT_S  单次查询超时(秒), 默认 10
 *   - SLOW_SQL_DB_MAX_ROWS         单次结果最多行数, 默认 5000 (防大表全量拉取)
 */
public record JdbcConfig(
        String jdbcUrl,
        String username,
        String password,
        int poolSize,
        Duration queryTimeout,
        int maxRows
) {

    public static JdbcConfig fromEnv() {
        return new JdbcConfig(
                envOrDefault("SLOW_SQL_DB_URL", "jdbc:mysql://localhost:3307/slow_sql_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
                envOrDefault("SLOW_SQL_DB_USER", "root"),
                envOrDefault("SLOW_SQL_DB_PASSWORD", "root"),
                Integer.parseInt(envOrDefault("SLOW_SQL_DB_POOL_SIZE", "4")),
                Duration.ofSeconds(Long.parseLong(envOrDefault("SLOW_SQL_DB_QUERY_TIMEOUT_S", "10"))),
                Integer.parseInt(envOrDefault("SLOW_SQL_DB_MAX_ROWS", "5000"))
        );
    }

    public boolean isConfigured() {
        return jdbcUrl != null && !jdbcUrl.isBlank();
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
