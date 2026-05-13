package com.slowsql.agent.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;

/**
 * 根据 LlmConfig 构造 OpenAI-compatible ChatModel.
 * MiMo / DeepSeek / Qwen 等都走同一条路, 只是 baseUrl + modelName + 厂商私有字段不同.
 *
 * 厂商私有字段(如 MiMo 的 thinking, Qwen 的 chat_template_kwargs)走
 * OpenAiChatRequestParameters.customParameters 透传, 由 LlmConfig.extraBody 注入.
 */
public final class ChatModelFactory {

    private ChatModelFactory() {}

    public static ChatModel build(LlmConfig config, List<ChatModelListener> listeners) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .temperature(config.temperature())
                .timeout(config.timeout())
                .logRequests(config.logRequests())
                .logResponses(config.logResponses())
                .listeners(listeners);

        if (config.extraBody() != null && !config.extraBody().isEmpty()) {
            builder.defaultRequestParameters(
                    OpenAiChatRequestParameters.builder()
                            .customParameters(config.extraBody())
                            .build());
        }
        return builder.build();
    }
}
