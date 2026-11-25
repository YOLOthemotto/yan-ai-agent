package com.ym.yanaiagent.sessionmemory.file;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
/**
 * 基于本地文件实现的 Spring AI Session 仓库。
 * Session 元数据写入 session.json，消息和其他事件追加写入 events.jsonl。
 */
@Slf4j
public final class FileSessionRepository implements SessionRepository {

    private static final int SCHEMA_VERSION = 1;

    private final FileSessionPaths paths;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final SessionEventCodec codec = new SessionEventCodec(jsonMapper);
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final Object eventIndexLock = new Object();

    /**
     * 初始化文件仓库并确保根目录存在。
     */
    public FileSessionRepository(Path rootDirectory) {
        Assert.notNull(rootDirectory, "rootDirectory must not be null");
        this.paths = new FileSessionPaths(rootDirectory);
        try {
            Files.createDirectories(paths.rootDirectory());
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to create session file directory: " + paths.rootDirectory(), ex);
        }
    }

    /**
     * 新建或更新 Session；更新时保留第一次创建时间和现有事件。
     */
    @Override
    public @NonNull Session save(@NonNull Session session) {
        Assert.notNull(session, "session must not be null");
        Assert.hasText(session.id(), "session id must not be null or empty");
        return withSessionLock(session.id(), () -> {
            SessionState existing = readStateIfPresent(session.id());
            Session saved = existing == null
                    ? session
                    : Session.builder()
                            .id(session.id())
                            .createdAt(existing.session().createdAt())
                            .expiresAt(session.expiresAt())
                            .metadata(session.metadata())
                            .build();
            writeSession(sessionToSnapshot(saved, existing == null ? 0L : existing.version()));
            return saved;
        });
    }

    /**
     * 只有 Session ID 尚不存在时才创建，供并发首次访问使用。
     */
    @Override
    public boolean saveIfAbsent(@NonNull Session session) {
        Assert.notNull(session, "session must not be null");
        Assert.hasText(session.id(), "session id must not be null or empty");
        return withSessionLock(session.id(), () -> {
            if (Files.exists(paths.sessionFile(session.id()))) {
                return false;
            }
            writeSession(sessionToSnapshot(session, 0L));
            return true;
        });
    }

    /**
     * 按 ID 读取 Session；不存在时返回 null。
     */
    @Override
    public @Nullable Session findById(@NonNull String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        return withSessionLock(sessionId, () -> {
            SessionState state = readStateIfPresent(sessionId);
            return state == null ? null : state.session();
        });
    }

    /**
     * 按 Session metadata 精确查找，例如 principalName。
     */
    @Override
    public @NonNull List<Session> findByMetadata(@NonNull String name, @NonNull String value) {
        Assert.hasText(name, "name must not be null or empty");
        Assert.notNull(value, "value must not be null");
        return readAllSessions().stream()
                .filter(session -> session.metadata().containsKey(name)
                        && value.equals(String.valueOf(session.metadata().get(name))))
                .sorted(Comparator.comparing(Session::createdAt).thenComparing(Session::id))
                .toList();
    }

    /**
     * 删除一个 Session 的元数据和全部事件。
     */
    @Override
    public void delete(@NonNull String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        synchronized (eventIndexLock) {
            withSessionLock(sessionId, () -> {
                deleteSessionDirectory(sessionId);
                return null;
            });
        }
    }

    /**
     * 删除过期 Session，并在删除前再次确认过期时间，避免并发续期被误删。
     */
    @Override
    public int deleteExpiredSessions(@NonNull Instant before) {
        Assert.notNull(before, "before must not be null");
        int deleted = 0;
        for (Session session : readAllSessions()) {
            Instant expiresAt = session.expiresAt();
            if (expiresAt == null || !expiresAt.isBefore(before)) {
                continue;
            }
            synchronized (eventIndexLock) {
                boolean removed = withSessionLock(session.id(), () -> {
                    SessionState current = readStateIfPresent(session.id());
                    if (current == null || current.session().expiresAt() == null
                            || !current.session().expiresAt().isBefore(before)) {
                        return false;
                    }
                    deleteSessionDirectory(session.id());
                    return true;
                });
                if (removed) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * 幂等追加单个事件；同一事件 ID 重试时不会重复写入。
     */
    @Override
    public void appendEvent(@NonNull SessionEvent event) {
        Assert.notNull(event, "event must not be null");
        Assert.hasText(event.getSessionId(), "event sessionId must not be null or empty");
        synchronized (eventIndexLock) {
            withSessionLock(event.getSessionId(), () -> {
                SessionState state = requireState(event.getSessionId());
                String owner = findEventOwner(event.getId(), event.getSessionId());
                if (owner != null) {
                    throw new IllegalStateException(
                            "Event id '" + event.getId() + "' is already used by session '" + owner + "'");
                }
                if (state.events().stream().anyMatch(existing -> existing.getId().equals(event.getId()))) {
                    return null;
                }
                long newVersion = state.version() + 1;
                appendEncodedEvents(event.getSessionId(), List.of(event), newVersion);
                writeSession(sessionToSnapshot(state.session(), newVersion));
                return null;
            });
        }
    }

    /**
     * 按 expectedVersion 原子追加多个事件，版本变化时返回 false。
     */
    @Override
    public boolean appendEvents(@NonNull String sessionId, @NonNull List<SessionEvent> events, long expectedVersion) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        Assert.notEmpty(events, "events must not be empty");
        synchronized (eventIndexLock) {
            return withSessionLock(sessionId, () -> {
                SessionState state = requireState(sessionId);
                if (state.version() != expectedVersion) {
                    return false;
                }

                Set<String> existingIds = new HashSet<>();
                state.events().forEach(event -> existingIds.add(event.getId()));
                Set<String> batchIds = new HashSet<>();
                for (SessionEvent event : events) {
                    Assert.isTrue(sessionId.equals(event.getSessionId()),
                            () -> "Event '" + event.getId() + "' belongs to session '" + event.getSessionId() + "'");
                    Assert.isTrue(!existingIds.contains(event.getId()) && batchIds.add(event.getId()),
                            () -> "Event id '" + event.getId() + "' is already in the log of session " + sessionId);
                    String owner = findEventOwner(event.getId(), sessionId);
                    if (owner != null) {
                        throw new IllegalStateException(
                                "Event id '" + event.getId() + "' is already used by session '" + owner + "'");
                    }
                }

                long newVersion = state.version() + 1;
                appendEncodedEvents(sessionId, events, newVersion);
                writeSession(sessionToSnapshot(state.session(), newVersion));
                return true;
            });
        }
    }

    /**
     * 返回事件日志当前版本号，供压缩操作做 CAS 写入。
     */
    @Override
    public long getEventVersion(@NonNull String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        return withSessionLock(sessionId, () -> {
            SessionState state = readStateIfPresent(sessionId);
            return state == null ? 0L : state.version();
        });
    }

    /**
     * 读取事件并应用 Spring AI 提供的 active、lastN 等过滤规则。
     */
    @Override
    public @NonNull List<SessionEvent> findEvents(@NonNull String sessionId, @NonNull EventFilter filter) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        Assert.notNull(filter, "filter must not be null");
        return withSessionLock(sessionId, () -> {
            SessionState state = readStateIfPresent(sessionId);
            return state == null ? List.of() : filter.apply(state.events());
        });
    }

    /**
     * 在完整事件日志中执行关键词、时间和消息类型搜索。
     */
    @Override
    public @NonNull List<SessionEvent> searchEvents(@NonNull String sessionId, @NonNull EventQuery query) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        Assert.notNull(query, "query must not be null");
        return withSessionLock(sessionId, () -> {
            SessionState state = readStateIfPresent(sessionId);
            return state == null ? List.of() : query.apply(state.events());
        });
    }

    /**
     * 读取必须存在的 Session，不存在时给出明确错误。
     */
    private SessionState requireState(String sessionId) {
        SessionState state = readStateIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return state;
    }

    /**
     * 从 session.json 和 events.jsonl 恢复一个完整内存状态。
     */
    private @Nullable SessionState readStateIfPresent(String sessionId) {
        Path sessionFile = paths.sessionFile(sessionId);
        if (!Files.exists(sessionFile)) {
            return null;
        }
        PersistedSession persistedSession = readJson(sessionFile, PersistedSession.class);
        if (persistedSession.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported session schema version "
                    + persistedSession.schemaVersion() + " in " + sessionFile);
        }
        List<PersistedEvent> persistedEvents = readPersistedEvents(sessionId);
        long version = persistedEvents.stream()
                .mapToLong(PersistedEvent::version)
                .max()
                .orElse(persistedSession.eventVersion());
        List<SessionEvent> events = persistedEvents.stream()
                .map(this::decodeEvent)
                .toList();
        return new SessionState(
                Session.builder()
                        .id(persistedSession.id())
                        .createdAt(Instant.parse(persistedSession.createdAt()))
                        .expiresAt(persistedSession.expiresAt() == null
                                ? null : Instant.parse(persistedSession.expiresAt()))
                        .metadata(persistedSession.metadata())
                        .build(),
                events,
                Math.max(persistedSession.eventVersion(), version));
    }

    /**
     * 读取 JSONL 事件；只允许截断文件末尾不完整的一行。
     */
    private List<PersistedEvent> readPersistedEvents(String sessionId) {
        Path eventsFile = paths.eventsFile(sessionId);
        if (!Files.exists(eventsFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
            List<PersistedEvent> events = new ArrayList<>();
            List<String> validLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    PersistedEvent event = jsonMapper.readValue(line, PersistedEvent.class);
                    events.add(event);
                    validLines.add(line);
                }
                catch (JacksonException ex) {
                    boolean isLastNonBlankLine = lines.subList(index + 1, lines.size()).stream()
                            .allMatch(String::isBlank);
                    if (!isLastNonBlankLine) {
                        throw new IllegalStateException("Corrupt event log entry in " + eventsFile
                                + " at line " + (index + 1), ex);
                    }
                    log.warn("Truncating incomplete final event log entry in {}", eventsFile);
                    replaceFileAtomically(eventsFile,
                            String.join(System.lineSeparator(), validLines)
                                    + (validLines.isEmpty() ? "" : System.lineSeparator()));
                    break;
                }
            }
            return events;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read event log: " + eventsFile, ex);
        }
    }

    /**
     * 扫描所有 Session 快照，用于 metadata 查询和过期清理。
     */
    private List<Session> readAllSessions() {
        if (!Files.exists(paths.rootDirectory())) {
            return List.of();
        }
        try (var directories = Files.list(paths.rootDirectory())) {
            List<Session> sessions = new ArrayList<>();
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                Path snapshot = directory.resolve(FileSessionPaths.SESSION_FILE);
                if (!Files.exists(snapshot)) {
                    continue;
                }
                PersistedSession persisted = readJson(snapshot, PersistedSession.class);
                sessions.add(Session.builder()
                        .id(persisted.id())
                        .createdAt(Instant.parse(persisted.createdAt()))
                        .expiresAt(persisted.expiresAt() == null ? null : Instant.parse(persisted.expiresAt()))
                        .metadata(persisted.metadata())
                        .build());
            }
            return sessions;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to list session directory: " + paths.rootDirectory(), ex);
        }
    }

    /**
     * 检查事件 ID 是否被其他 Session 占用。
     */
    private @Nullable String findEventOwner(String eventId, String currentSessionId) {
        for (Session session : readAllSessions()) {
            if (session.id().equals(currentSessionId)) {
                continue;
            }
            if (readPersistedEvents(session.id()).stream()
                    .anyMatch(event -> event.id().equals(eventId))) {
                return session.id();
            }
        }
        return null;
    }

    /**
     * 使用官方 Codec 编码事件并追加到 JSONL，写入后强制落盘。
     */
    private void appendEncodedEvents(String sessionId, List<SessionEvent> events, long version) {
        StringBuilder lines = new StringBuilder();
        for (SessionEvent event : events) {
            SessionEventCodec.EncodedEvent encoded = codec.encode(event);
            PersistedEvent persisted = new PersistedEvent(
                    SCHEMA_VERSION,
                    version,
                    event.getId(),
                    event.getSessionId(),
                    event.getTimestamp().toString(),
                    encoded.type().name(),
                    encoded.messageType() == null ? null : encoded.messageType().name(),
                    encoded.text(),
                    encoded.payload(),
                    event.getMetadata());
            lines.append(toJson(persisted)).append(System.lineSeparator());
        }

        Path eventsFile = paths.eventsFile(sessionId);
        try {
            Files.createDirectories(eventsFile.getParent());
            try (FileChannel channel = FileChannel.open(eventsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to append events for session " + sessionId, ex);
        }
    }

    /**
     * 将持久化事件还原为 Spring AI 的 SessionEvent。
     */
    private SessionEvent decodeEvent(PersistedEvent event) {
        SessionEventCodec.EncodedEvent encoded = new SessionEventCodec.EncodedEvent(
                EventType.of(event.eventType()),
                event.messageType() == null ? null : MessageType.valueOf(event.messageType()),
                event.text(),
                event.payload());
        return codec.decode(encoded)
                .id(event.id())
                .sessionId(event.sessionId())
                .timestamp(Instant.parse(event.timestamp()))
                .metadata(event.metadata())
                .build();
    }

    /**
     * 将 Session 转换为可直接写入 session.json 的快照。
     */
    private PersistedSession sessionToSnapshot(Session session, long version) {
        return new PersistedSession(
                SCHEMA_VERSION,
                session.id(),
                session.createdAt().toString(),
                session.expiresAt() == null ? null : session.expiresAt().toString(),
                session.metadata(),
                version);
    }

    /**
     * 原子更新 Session 快照，并确保事件文件存在。
     */
    private void writeSession(PersistedSession session) {
        Path directory = paths.sessionDirectory(session.id());
        try {
            Files.createDirectories(directory);
            replaceFileAtomically(paths.sessionFile(session.id()), toJson(session));
            if (!Files.exists(paths.eventsFile(session.id()))) {
                Files.createFile(paths.eventsFile(session.id()));
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to persist session " + session.id(), ex);
        }
    }

    /**
     * 递归删除一个 Session 目录；目录路径由内部哈希规则生成。
     */
    private void deleteSessionDirectory(String sessionId) {
        Path directory = paths.sessionDirectory(sessionId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to delete session " + sessionId, ex);
        }
    }

    /**
     * 通过临时文件和原子重命名替换目标文件，避免写入一半。
     */
    private void replaceFileAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 读取并反序列化一个 JSON 文件。
     */
    private <T> T readJson(Path file, Class<T> type) {
        try {
            return jsonMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        }
        catch (IOException | JacksonException ex) {
            throw new IllegalStateException("Failed to read JSON file: " + file, ex);
        }
    }

    /**
     * 将对象序列化为一行 JSON。
     */
    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        }
        catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize file session data", ex);
        }
    }

    /**
     * 同一个 Session 在本进程内串行读写，避免并发覆盖。
     */
    private <T> T withSessionLock(String sessionId, Supplier<T> action) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * 一次磁盘读取后的完整 Session 状态。
     */
    private record SessionState(Session session, List<SessionEvent> events, long version) {
    }

    /**
     * session.json 的稳定存储结构。
     */
    private record PersistedSession(
            int schemaVersion,
            String id,
            String createdAt,
            @Nullable String expiresAt,
            Map<String, Object> metadata,
            long eventVersion) {
    }

    /**
     * events.jsonl 中每一行对应的稳定存储结构。
     */
    private record PersistedEvent(
            int schemaVersion,
            long version,
            String id,
            String sessionId,
            String timestamp,
            String eventType,
            @Nullable String messageType,
            @Nullable String text,
            String payload,
            Map<String, Object> metadata) {
    }
}
