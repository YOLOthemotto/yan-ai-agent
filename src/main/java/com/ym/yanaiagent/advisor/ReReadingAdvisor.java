package com.ym.yanaiagent.advisor;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

public class ReReadingAdvisor implements BaseAdvisor {

    private static final String DEFAULT_TEMPLATE = """
            {question}
            请再次认真阅读这个问题：{question}
            """;

    private final String template;
    private final int order;

    public ReReadingAdvisor() {
        this(DEFAULT_TEMPLATE, 0);
    }

    public ReReadingAdvisor(String template) {
        this(template, 0);
    }

    public ReReadingAdvisor(String template, int order) {
        this.template = template;
        this.order = order;
    }

    @Override
    public @NonNull ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain chain) {
        String question = request.prompt().getUserMessage().getText();
        String repeatedQuestion = PromptTemplate.builder()
                .template(template)
                .variables(Map.of("question", question))
                .build()
                .render();

        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(repeatedQuestion))
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
