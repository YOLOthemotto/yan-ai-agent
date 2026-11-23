package com.ym.yanaiagent.controller;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/demo/openai")
public class OpenAiDemoController {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一名严谨、友好的 Java AI 编程导师。请使用中文回答，并给出简洁示例。";

    private final ChatModel chatCompletionsModel;
    private final String responsesBaseUrl;
    private final String responsesApiKey;
    private final String defaultModel;

    public OpenAiDemoController(
            ChatModel chatCompletionsModel,
            OpenAiConnectionDetails connectionDetails,
            @Value("${spring.ai.openai.chat.options.model:${spring.ai.openai.chat.model:gpt-4o-mini}}")
            String defaultModel) {
        this.chatCompletionsModel = chatCompletionsModel;
        this.responsesBaseUrl = connectionDetails.getBaseUrl();
        this.responsesApiKey = connectionDetails.getApiKey();
        this.defaultModel = defaultModel;
    }

    /**
     * Chat Completions（/v1/chat/completions）同步调用示例。
     */
    @GetMapping("/chat-completions")
    public ChatDemoResponse chatCompletions(
            @RequestParam(defaultValue = "用三句话介绍 Spring AI") String prompt,
            @RequestParam(defaultValue = DEFAULT_SYSTEM_PROMPT) String systemPrompt,
            @RequestParam(defaultValue = "low") String reasoningEffort) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(defaultModel)
                .reasoningEffort(reasoningEffort)
                .build();
        AssistantMessage message = chatCompletionsModel.call(new Prompt(messages(systemPrompt, prompt), options))
                .getResult()
                .getOutput();
        return toResponse(message);
    }

    /**
     * Responses API（/v1/responses）同步调用示例。
     * 中转服务必须实现该协议，思考摘要通过 reasoningSummary 返回。
     */
    @GetMapping("/responses")
    public ChatDemoResponse responses(
            @RequestParam(defaultValue = "用三句话介绍 Spring AI") String prompt,
            @RequestParam(defaultValue = DEFAULT_SYSTEM_PROMPT) String systemPrompt,
            @RequestParam(defaultValue = "medium") String reasoningEffort,
            @RequestParam(defaultValue = "auto") String reasoningSummary) {
        OpenAiResponsesChatOptions options = OpenAiResponsesChatOptions.builder()
                .baseUrl(responsesBaseUrl)
                .apiKey(responsesApiKey)
                .model(defaultModel)
                .reasoningEffort(reasoningEffort)
                .reasoningSummary(reasoningSummary)
                .build();
        OpenAiResponsesChatModel responsesModel = OpenAiResponsesChatModel.builder()
                .options(options)
                .build();

        AssistantMessage message = responsesModel.call(new Prompt(messages(systemPrompt, prompt), options))
                .getResult()
                .getOutput();
        return toResponse(message);
    }

    /**
     * Responses API 流式输出示例，返回仅包含回答文本的 SSE。
     */
    @GetMapping(path = "/responses/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamResponses(
            @RequestParam(defaultValue = "给 Java AI 初学者一份学习路线") String prompt,
            @RequestParam(defaultValue = DEFAULT_SYSTEM_PROMPT) String systemPrompt,
            @RequestParam(defaultValue = "low") String reasoningEffort) {
        OpenAiResponsesChatOptions options = OpenAiResponsesChatOptions.builder()
                .baseUrl(responsesBaseUrl)
                .apiKey(responsesApiKey)
                .model(defaultModel)
                .reasoningEffort(reasoningEffort)
                .reasoningSummary("auto")
                .build();
        OpenAiResponsesChatModel responsesModel = OpenAiResponsesChatModel.builder()
                .options(options)
                .build();

        return responsesModel.stream(new Prompt(messages(systemPrompt, prompt), options))
                .map(response -> response.getResult().getOutput().getText())
                .filter(StringUtils::hasText);
    }

    private static List<Message> messages(String systemPrompt, String prompt) {
        return List.of(new SystemMessage(systemPrompt), new UserMessage(prompt));
    }

    private static ChatDemoResponse toResponse(AssistantMessage message) {
        String reasoning = message.getReasoning()
                .stream()
                .map(ReasoningPart::text)
                .filter(StringUtils::hasText)
                .reduce("", (left, right) -> left + right);
        return new ChatDemoResponse(message.getText(), reasoning);
    }

    public record ChatDemoResponse(String answer, String reasoningSummary) {
    }
}
