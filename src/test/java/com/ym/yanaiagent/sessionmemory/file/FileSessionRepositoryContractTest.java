package com.ym.yanaiagent.sessionmemory.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.test.session.AbstractSessionRepositoryContractTests;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Spring AI 官方契约测试文件版 SessionRepository，并额外验证重建仓库后可恢复。
 */
class FileSessionRepositoryContractTest extends AbstractSessionRepositoryContractTests {

    @TempDir
    Path tempDirectory;

    /**
     * 每个契约测试使用独立临时目录，避免测试间共享数据。
     */
    @Override
    protected SessionRepository createRepository() {
        return new FileSessionRepository(tempDirectory.resolve("sessions"));
    }

    /**
     * 模拟服务重启后创建新的 Repository 实例。
     */
    @Test
    void loadsMessagesAfterRepositoryIsRecreated() {
        Session session = repository.save(newSession("restart-session"));
        repository.appendEvent(event(session.id(), user("重启前的问题")));
        repository.appendEvent(event(session.id(), assistant("重启前的回答")));

        SessionRepository reloadedRepository = new FileSessionRepository(tempDirectory.resolve("sessions"));

        List<String> texts = reloadedRepository.findEvents(session.id(), org.springframework.ai.session.EventFilter.all())
                .stream()
                .map(SessionEvent::getMessage)
                .map(message -> message.getText())
                .toList();
        assertThat(texts).containsExactly("重启前的问题", "重启前的回答");
    }
}
