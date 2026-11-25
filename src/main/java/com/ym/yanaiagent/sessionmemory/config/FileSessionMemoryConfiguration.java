package com.ym.yanaiagent.sessionmemory.config;

import com.ym.yanaiagent.sessionmemory.file.FileSessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 仅在 session-file Profile 下注册文件版 SessionRepository。
 * 该 Bean 一旦注册，Spring AI 的官方 SessionService 自动配置会复用它。
 */
@Configuration(proxyBeanMethods = false)
@Profile("session-file")
@EnableConfigurationProperties(SessionMemoryProperties.class)
public class FileSessionMemoryConfiguration {

    /**
     * 创建文件存储实现，并将它作为应用唯一的 SessionRepository。
     */
    @Bean
    SessionRepository fileSessionRepository(SessionMemoryProperties properties) {
        return new FileSessionRepository(properties.getRootDirectory());
    }
}
