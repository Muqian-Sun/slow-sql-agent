package com.slowsql.agent.config;

import com.slowsql.agent.llm.LlmConfig;
import com.slowsql.agent.tools.DataSourceFactory;
import com.slowsql.agent.tools.JdbcConfig;
import com.slowsql.agent.tools.JdbcToolBackend;
import com.slowsql.agent.tools.ToolBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 把 LlmConfig / DataSource / ToolBackend 三个共享资源装到 Spring 容器.
 * 配置源仍是环境变量 ({@link LlmConfig#fromEnv()} / {@link JdbcConfig#fromEnv()}),
 * Spring 只负责生命周期管理 + 注入, 不重复定义 properties — 这样 IT 测试 / 命令行用法
 * 可以继续直接调 fromEnv() 不依赖 Spring 启动.
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public LlmConfig llmConfig() {
        LlmConfig cfg = LlmConfig.fromEnv();
        if (!cfg.isApiKeyPresent()) {
            log.warn("SLOW_SQL_LLM_API_KEY 未设置: /api/diagnose 接到请求时会返回 5xx. " +
                    "若仅启动检查配置可忽略.");
        }
        return cfg;
    }

    @Bean
    public JdbcConfig jdbcConfig() {
        return JdbcConfig.fromEnv();
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(JdbcConfig jdbcConfig) {
        return DataSourceFactory.build(jdbcConfig);
    }

    @Bean
    public ToolBackend toolBackend(DataSource dataSource, JdbcConfig jdbcConfig) {
        return new JdbcToolBackend(dataSource, jdbcConfig);
    }
}
