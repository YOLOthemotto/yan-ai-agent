package com.ym.yanaiagent.sessionmemory.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 统一生成文件 Session 的安全路径，Session ID 不会直接作为目录名。
 */
record FileSessionPaths(Path rootDirectory) {

    static final String SESSION_FILE = "session.json";
    static final String EVENTS_FILE = "events.jsonl";

    /**
     * 将根目录转换为规范化绝对路径。
     */
    FileSessionPaths(Path rootDirectory) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
    }

    /**
     * 使用 Session ID 的 SHA-256 摘要生成独立目录。
     */
    Path sessionDirectory(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return rootDirectory.resolve(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Session 元数据文件路径。
     */
    Path sessionFile(String sessionId) {
        return sessionDirectory(sessionId).resolve(SESSION_FILE);
    }

    /**
     * Session 事件日志文件路径。
     */
    Path eventsFile(String sessionId) {
        return sessionDirectory(sessionId).resolve(EVENTS_FILE);
    }
}
