package com.ym.yanaiagent.sessionmemory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 文件模式的可配置项，目前只需要 Session 数据根目录。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "session-memory.file")
public class SessionMemoryProperties {

    /**
     * 文件 Session 的根目录，默认保存在当前项目的 data 目录。
     */
    private Path rootDirectory = Path.of("./data/sessions");

}
