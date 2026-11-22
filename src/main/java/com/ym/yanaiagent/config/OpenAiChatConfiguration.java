package com.ym.yanaiagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("openai")
@ConditionalOnProperty(prefix = "ai-providers.openai.chat", name = "enabled", havingValue = "true")
public class OpenAiChatConfiguration {

    @Bean
    public ChatModel openAiChatModel(
            @Value("${ai-providers.openai.chat.base-url}") String baseUrl,
            @Value("${ai-providers.openai.chat.api-key}") String apiKey,
            @Value("${ai-providers.openai.chat.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }
}
