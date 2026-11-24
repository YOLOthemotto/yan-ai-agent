package com.ym.yanaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private final int order;

    public MyLoggerAdvisor() {
        this(0);
    }

    public MyLoggerAdvisor(int order) {
        this.order = order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        ChatClientResponse response = chain.nextCall(request);
        logResponse(response);
        return response;
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(chain.nextStream(request), this::logResponse);
    }

    protected void logRequest(ChatClientRequest request) {
        log.info("Spring AI request messages: {}", formatMessages(request.prompt().getInstructions()));
        log.info("Spring AI model options: {}", formatOptions(request.prompt().getOptions()));
    }

    protected void logResponse(ChatClientResponse response) {
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            log.info("Spring AI response: null");
            return;
        }
        log.info("Spring AI response: answer={}, usage={}",
                chatResponse.getResult().getOutput().getText(),
                chatResponse.getMetadata().getUsage());
    }

    private static List<String> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(message -> message.getMessageType() + ": " + message.getText())
                .toList();
    }

    private static String formatOptions(ChatOptions options) {
        if (options instanceof OpenAiResponsesChatOptions openAiOptions) {
            return "OpenAiResponsesChatOptions{" +
                    "model=" + openAiOptions.getModel() +
                    ", maxOutputTokens=" + openAiOptions.getMaxOutputTokens() +
                    ", temperature=" + openAiOptions.getTemperature() +
                    ", topP=" + openAiOptions.getTopP() +
                    ", reasoningEffort=" + openAiOptions.getReasoningEffort() +
                    ", reasoningSummary=" + openAiOptions.getReasoningSummary() +
                    '}';
        }
        if (options instanceof OpenAiChatOptions openAiOptions) {
            return "OpenAiChatOptions{" +
                    "model=" + openAiOptions.getModel() +
                    ", maxTokens=" + openAiOptions.getMaxTokens() +
                    ", maxCompletionTokens=" + openAiOptions.getMaxCompletionTokens() +
                    ", temperature=" + openAiOptions.getTemperature() +
                    ", topP=" + openAiOptions.getTopP() +
                    ", reasoningEffort=" + openAiOptions.getReasoningEffort() +
                    '}';
        }
        return options == null ? "null"
                : options.getClass().getSimpleName() + "{model=" + options.getModel()
                + ", maxTokens=" + options.getMaxTokens()
                + ", temperature=" + options.getTemperature()
                + ", topP=" + options.getTopP() + "}";
    }

    @Override
    public @NonNull String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }
}
