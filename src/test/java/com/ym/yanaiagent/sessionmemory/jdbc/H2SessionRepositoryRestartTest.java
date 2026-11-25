package com.ym.yanaiagent.sessionmemory.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 H2 文件数据库关闭并重新打开后，Spring AI Session 历史仍然存在。
 */
class H2SessionRepositoryRestartTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsMessagesAfterH2IsReopened() {
        String jdbcUrl = "jdbc:h2:file:" + tempDirectory.resolve("session-memory")
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE";

        try (HikariDataSource firstDataSource = dataSource(jdbcUrl)) {
            initializeSchema(firstDataSource);
            SessionRepository firstRepository = JdbcSessionRepository.builder()
                    .dataSource(firstDataSource)
                    .build();
            Session session = Session.builder()
                    .id("h2-restart-session")
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .metadata(Map.of())
                    .build();
            firstRepository.save(session);
            firstRepository.appendEvent(message(session.id(), new UserMessage("重启前的问题")));
            firstRepository.appendEvent(message(session.id(), new AssistantMessage("重启前的回答")));
        }

        try (HikariDataSource secondDataSource = dataSource(jdbcUrl)) {
            SessionRepository secondRepository = JdbcSessionRepository.builder()
                    .dataSource(secondDataSource)
                    .build();
            List<String> texts = secondRepository.findEvents("h2-restart-session", EventFilter.all())
                    .stream()
                    .map(event -> event.getMessage().getText())
                    .toList();
            assertThat(texts).containsExactly("重启前的问题", "重启前的回答");
        }
    }

    private static HikariDataSource dataSource(String jdbcUrl) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void initializeSchema(HikariDataSource dataSource) {
        new ResourceDatabasePopulator(
                new ClassPathResource("org/springframework/ai/session/jdbc/schema-h2.sql")
        ).execute(dataSource);
    }

    private static SessionEvent message(String sessionId, org.springframework.ai.chat.messages.Message message) {
        return SessionEvent.builder()
                .sessionId(sessionId)
                .message(message)
                .build();
    }
}
