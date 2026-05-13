package com.slowsql.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 工具返回结果的 JSON 序列化入口.
 *
 * 约定:
 *   - 用 snake_case 字段名(LLM 看到的全部小写下划线, 与 OpenAI / Anthropic 工具协议常见风格一致)
 *   - NON_NULL 跳过空字段(同状态下不相关的字段不出现, JSON 更干净)
 *   - 失败时返回一个最小化的 fallback JSON, 不抛异常 — 工具层永远要给 LLM 一个能读的 string
 */
final class ToolJson {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ToolJson() {}

    static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"reason\":\"json_serialize_fail\",\"message\":\""
                    + e.getClass().getSimpleName() + "\"}";
        }
    }
}
