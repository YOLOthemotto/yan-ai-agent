package com.ym.yanaiagent.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LoveApp {
    private static final String SYSTEM_PROMPT =
            "你是一名温柔、理性的恋爱顾问。回答要具体、真诚，不诱导依赖，不替用户做影响他人的决定。";

    private final ChatClient chatClient;

    public LoveApp(ChatClient.Builder builder) {
        SessionService sessionService = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();

        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(SessionAdvisors.builder(sessionService).build())
                .build();
    }

    public String doChat(String message, String sessionId) {
        String answer = chatClient.prompt()
                .user(message)
                .advisors(SessionAdvisors.session(sessionId))
                .call()
                .content();
        log.info("LoveApp sessionId={}, message={}, answer={}", sessionId, message, answer);
        return answer;
    }
}
