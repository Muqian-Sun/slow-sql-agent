package com.slowsql.agent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

/**
 * LLM 调用配置.
 *
 * 从环境变量加载,避免在代码 / 仓库里硬编码 endpoint 与密钥.
 *   - SLOW_SQL_LLM_BASE_URL    OpenAI-compatible API base url
 *   - SLOW_SQL_LLM_API_KEY     API key
 *   - SLOW_SQL_LLM_MODEL       model name (默认 gpt-4o-mini, 接 MiMo 等时覆盖)
 *   - SLOW_SQL_LLM_TIMEOUT_S   单次请求超时(秒), 默认 60
 *   - SLOW_SQL_LLM_TEMP        temperature, 默认 0.2
 *   - SLOW_SQL_LLM_EXTRA_BODY  额外塞进 HTTP 请求 body 的 JSON 对象
 *                              例 MiMo 关推理模式: {"thinking":{"type":"disabled"}}
 *                              通过 OpenAiChatRequestParameters.customParameters 透传
 */
public record LlmConfig(
        String baseUrl,
        String apiKey,
        String modelName,
        Duration timeout,
        double temperature,
        boolean logRequests,
        boolean logResponses,
        Map<String, Object> extraBody
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static LlmConfig fromEnv() {
        String baseUrl = envOrDefault("SLOW_SQL_LLM_BASE_URL", "https://api.openai.com/v1");
        String apiKey = envOrDefault("SLOW_SQL_LLM_API_KEY", "");
        String model = envOrDefault("SLOW_SQL_LLM_MODEL", "gpt-4o-mini");
        long timeoutS = Long.parseLong(envOrDefault("SLOW_SQL_LLM_TIMEOUT_S", "60"));
        double temp = Double.parseDouble(envOrDefault("SLOW_SQL_LLM_TEMP", "0.2"));
        boolean logReq = Boolean.parseBoolean(envOrDefault("SLOW_SQL_LLM_LOG_REQUESTS", "false"));
        boolean logResp = Boolean.parseBoolean(envOrDefault("SLOW_SQL_LLM_LOG_RESPONSES", "false"));
        Map<String, Object> extraBody = parseExtraBody(envOrDefault("SLOW_SQL_LLM_EXTRA_BODY", ""));
        return new LlmConfig(baseUrl, apiKey, model, Duration.ofSeconds(timeoutS), temp, logReq, logResp, extraBody);
    }

    public boolean isApiKeyPresent() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static Map<String, Object> parseExtraBody(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "SLOW_SQL_LLM_EXTRA_BODY 必须是合法 JSON 对象: " + e.getMessage(), e);
        }
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
