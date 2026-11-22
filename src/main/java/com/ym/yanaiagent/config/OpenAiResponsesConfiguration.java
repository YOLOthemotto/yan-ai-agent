package com.ym.yanaiagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("openai-responses")
@ConditionalOnProperty(prefix = "ai-providers.openai.responses", name = "enabled", havingValue = "true")
public class OpenAiResponsesConfiguration {

    @Bean
    public ChatModel openAiResponsesChatModel(
            @Value("${ai-providers.openai.responses.base-url}") String baseUrl,
            @Value("${ai-providers.openai.responses.api-key}") String apiKey,
            @Value("${ai-providers.openai.responses.model-name}") String modelName) {
        return OpenAiResponsesChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .reasoningEffort("medium")
                .build();
    }
}
