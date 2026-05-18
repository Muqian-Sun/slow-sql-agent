package com.slowsql.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 服务入口. 启动:
 *   mvn package && java -jar target/slow-sql-agent-*.jar
 * 或开发模式:
 *   mvn spring-boot:run
 *
 * 环境变量同 IT 测试:
 *   SLOW_SQL_LLM_BASE_URL / SLOW_SQL_LLM_API_KEY / SLOW_SQL_LLM_MODEL / SLOW_SQL_LLM_EXTRA_BODY
 *   SLOW_SQL_DB_URL / SLOW_SQL_DB_USER / SLOW_SQL_DB_PASSWORD
 *
 * 服务起来后, 唯一对外端点是 POST /api/diagnose (见 DiagnoseController).
 *
 * 设计取舍: agent 实例 per-request 创建, 不做 Spring bean (per-call stats 隔离).
 * 共享资源 (DataSource / ToolBackend / LlmConfig) 走 Bean 复用.
 */
@SpringBootApplication
public class SlowSqlAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SlowSqlAgentApplication.class, args);
    }
}
