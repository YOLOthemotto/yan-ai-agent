# Spring AI Session 持久化

项目支持三种 Session 存储方式，业务代码只依赖 `SessionService`，无需判断当前使用哪一种后端。

```text
LoveApp
  -> ChatClient + SessionAdvisors
  -> SessionService
  -> SessionRepository
     -> FileSessionRepository
     -> JdbcSessionRepository + H2
     -> JdbcSessionRepository + PostgreSQL
```

## 默认：PostgreSQL

`application.yaml` 默认启用：

```yaml
spring.profiles.active: local,session-postgres
```

启动本地数据库：

```bash
cp .env.example .env
docker compose up -d postgres
```

默认连接参数：

| 项目 | 默认值 |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `yan_ai_agent` |
| Username | `yanai` |
| Password | `yanai123` |
| JDBC URL | `jdbc:postgresql://localhost:5432/yan_ai_agent` |

Docker 数据保存在命名卷 `yan-ai-agent-postgres-data` 中。删除容器不会删除该卷；只有明确执行 `docker compose down -v` 才会删除数据。

Docker 使用 `pgvector/pgvector:pg18-trixie` 镜像，包含 PostgreSQL 18 和 pgvector。首次初始化数据卷时，`docker/postgres/init/01-pgvector.sql` 会在 `POSTGRES_DB` 指定的数据库中自动启用 `vector` 扩展，用于存储 Embedding 和执行向量相似度检索。

如果复用已有数据卷，初始化脚本不会重新执行。更换镜像后需要手动启用一次：

```bash
docker compose up -d --wait postgres
docker compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS vector;"'
```

扩展按数据库启用；新建其他数据库后，也需要在对应数据库中执行 `CREATE EXTENSION IF NOT EXISTS vector;`。启用扩展不会自动创建 RAG 文档表或向量索引。

### PgVector 与 Embedding 配置

项目使用 Spring AI `2.1.0`，通过 `spring-ai-starter-vector-store-pgvector` 自动配置 `VectorStore`。默认的 `session-postgres` Profile 同时启用 PgVector，复用现有 PostgreSQL 数据源；H2 和文件模式不启用 PgVector。

应用启动后会创建 `public.vector_store` 表和 HNSW 索引，使用余弦距离检索。显式指定的 `1536` 维让建表不必调用 Embedding 服务；`remove-existing-vector-store-table: false` 防止启动时删除已有向量表。表在应用首次启动时创建，而不是 Docker 启动时创建。

当前 `text-embedding-3-small` 和 `1536` 维仅为预填参数，尚未验证中转服务是否支持。没有可用模型时可以启动应用，但调用 `VectorStore.add(...)` 或 `similaritySearch(...)` 会请求 Embedding 服务并可能失败。暂时没有接入文档导入或聊天 RAG 检索。

确定服务后，在运行应用的环境变量中设置：

| 环境变量 | 当前默认值 | 用途 |
|---|---|---|
| `EMBEDDING_BASE_URL` | 复用 `spring.ai.openai.base-url` | Embedding 服务地址 |
| `EMBEDDING_API_KEY` | 复用 `spring.ai.openai.api-key` | Embedding 服务密钥 |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | 服务实际支持的 Embedding 模型 |
| `EMBEDDING_DIMENSIONS` | `1536` | 同时配置模型输出维度和向量表维度 |

Spring AI `2.1.0` 的属性是 `spring.ai.openai.embedding.model` 和 `spring.ai.openai.embedding.dimensions`。维度参数仅适用于支持指定输出维度的模型；如果服务不接受此参数，应删除模型侧的 `dimensions` 配置，保留 PgVector 侧的维度并与模型实际输出保持一致。

`.env.example` 仅为参数示例。Docker Compose 会读取 `.env`，但本项目的 Spring Boot 应用未配置自动读取它；请在 IDEA Run Configuration 或启动进程的环境变量中设置 Embedding 参数。

已有表不会因修改配置自动改变维度。更换模型后需要重新生成已有文档的向量；如果维度也变化，还需迁移向量表或在备份后重建，不能直接复用旧向量。

官方文档：[PgVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)、[OpenAI Embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html)。

Spring AI 启动时执行官方 PostgreSQL schema，创建：

- `AI_SESSION`
- `AI_SESSION_INDEX`
- `AI_SESSION_EVENT`

### `AI_SESSION`：会话基本信息表

一行代表一个 Session，相当于聊天会话的“主记录”。

| 字段 | 用途 |
|---|---|
| `id` | Session ID，由业务传给 `SessionAdvisors.session(sessionId)` |
| `created_at` | Session 第一次创建时间，更新 Session 时保持不变 |
| `expires_at` | 过期时间；为空表示不过期 |
| `metadata` | JSON 文本，可保存用户标识、Agent 名称、模型等扩展属性 |
| `event_version` | 当前事件版本号，每次成功追加新事件后增加，用于并发冲突检测 |

`event_version` 不是消息条数。它是乐观锁版本：读取历史后若其他请求先写入新事件，旧请求就不能错误覆盖并发结果。

### `AI_SESSION_INDEX`：可检索元数据索引表

这张表不保存对话正文，只保存允许快速查询的 Session metadata。

例如把登录用户 ID 放入 metadata 后，可以通过 Spring AI 的 `findByMetadata(...)` 查询该用户有哪些 Session。

| 字段 | 用途 |
|---|---|
| `session_id` | 关联 `AI_SESSION.id` |
| `attr_name` | metadata 属性名，例如 `principalName` |
| `attr_value` | 属性值的前 512 个字符，用于数据库索引过滤 |

索引只加速筛选，Spring AI 最终仍会校验完整 metadata 值，避免前缀相同导致误匹配。删除 Session 时该 Session 对应的索引也会级联删除。

### `AI_SESSION_EVENT`：会话事件日志表

这是最核心的聊天记录表。一行代表一个事件，不只包含普通文本消息，还可以表示工具调用、压缩边界和应用自定义事件。

| 字段 | 用途 |
|---|---|
| `seq` | 数据库生成的顺序号，保证读取时按先后顺序还原 |
| `id` | 事件唯一 ID；全局唯一，重复追加相同 ID 时会被当作幂等重试 |
| `session_id` | 所属 Session |
| `timestamp` | 事件产生时间 |
| `event_type` | 事件类型，最常见的是 `MESSAGE`，压缩时还会有 `COMPACTION` |
| `message_type` | 消息角色，例如 `SYSTEM`、`USER`、`ASSISTANT`、`TOOL` |
| `message_content` | 纯文本内容，便于搜索和列表展示 |
| `payload` | JSON 完整载荷，保存 MessagePart、工具调用、图片和推理等结构化内容 |
| `synthetic` | 是否为压缩产生的摘要事件 |
| `metadata` | 事件元数据，包含来源、工具或压缩相关信息 |

三张表的关系是：

```text
AI_SESSION 1
├── N AI_SESSION_INDEX
└── N AI_SESSION_EVENT
```

`AI_SESSION_EVENT` 采用追加日志设计：旧事件不会因为压缩而被修改或覆盖。压缩只追加一个边界和摘要事件，再通过 active view 决定下一次给模型发送哪些内容。因此：

- `SessionHistoryService.getMessages()`：读取完整历史。
- `SessionHistoryService.getActiveMessages()`：读取当前模型上下文。
- 删除 `AI_SESSION` 时，索引和事件会通过数据库外键级联删除。

## IDEA 数据库连接

如果自动创建数据源不可用，可在 IDEA Database 工具窗口手动添加 PostgreSQL：

1. Host：`localhost`
2. Port：`5432`
3. Database：`yan_ai_agent`
4. User：`yanai`
5. Password：`yanai123`
6. 点击 Test Connection，再应用连接。

## H2 文件模式

启动参数：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,session-h2
```

数据库文件位于项目目录：

```text
./data/h2/session-memory.mv.db
```

H2 是嵌入式数据库，不需要安装服务，也不需要 Docker。`/data/` 已加入 `../.gitignore`。

## 文件模式

启动参数：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,session-file
```

数据位于：

```text
./data/sessions/<session-id-sha256>/
├── session.json
└── events.jsonl
```

每个 Session 使用 SHA-256 目录名，避免将原始 Session ID 直接写入路径。事件使用 Spring AI 官方 `SessionEventCodec` 序列化，因此可保留 MessagePart、工具调用、推理内容和 metadata。

文件模式只推荐单机学习，不适合多进程共享或高并发生产部署。

## 新增代码职责

| 类 | 作用 | 需要学习的重点 |
|---|---|---|
| `LoveApp` | 构建 `ChatClient` 并通过 `doChat` 发起对话 | 只依赖 `SessionService`，不关心底层是文件、H2 还是 PostgreSQL |
| `FileSessionMemoryConfiguration` | 在 `session-file` Profile 下注册文件版 `SessionRepository` | Spring 条件配置；官方 `SessionService` 会自动复用这个 Repository |
| `SessionMemoryProperties` | 读取 `session-memory.file.*` 配置 | Spring Boot `@ConfigurationProperties` 的基本用法 |
| `FileSessionRepository` | 实现文件存储的 `SessionRepository` | 仓库契约、事件追加、幂等重试、并发版本号、过滤和搜索 |
| `FileSessionPaths` | 将 Session ID 转为安全文件路径 | 使用 SHA-256 避免路径穿越，不把 ID 直接作为目录名 |
| `SessionHistoryService` | 给业务代码提供读取、搜索和删除历史的方法 | 区分完整历史和模型当前上下文 |
| `FileSessionRepositoryContractTest` | 运行 Spring AI 官方 Repository 契约测试 | 验证自定义仓库是否与官方语义一致 |
| `H2SessionRepositoryRestartTest` | 模拟关闭并重新打开 H2 | 验证重启后事件仍能恢复 |

### 整体调用链

一次 `LoveApp.doChat()` 的流程如下：

```text
LoveApp.doChat(message, sessionId)
  -> ChatClient.prompt()
  -> SessionAdvisors.session(sessionId)
  -> SessionMemoryAdvisor 读取该 Session 的有效历史
  -> OpenAI ChatModel 生成回答
  -> SessionTurnAdvisor / SessionMemoryAdvisor 写回用户消息和模型回答
  -> SessionService
  -> 当前 Profile 对应的 SessionRepository
```

不同存储方式只替换最后一层：

```text
FileSessionRepository        # 自己实现
JdbcSessionRepository + H2   # Spring AI 官方实现
JdbcSessionRepository + PG   # Spring AI 官方实现
```

所以 H2 和 PostgreSQL 不需要再写一个 JDBC Repository，Spring AI 已经处理建表、事务、事件顺序和并发。

### FileSessionRepository 阅读顺序

`FileSessionRepository` 比其他类大，建议按以下顺序阅读：

1. 先看 `save`、`saveIfAbsent`、`findById`：理解 Session 元数据。
2. 再看 `appendEvent`、`appendEvents`、`getEventVersion`：理解事件追加和并发版本。
3. 然后看 `findEvents`、`searchEvents`：理解完整历史、active view 和过滤。
4. 最后看 `SessionEventCodec` 相关方法：理解为什么不能只保存 `Message.getText()`。

文件中有两种数据：

```text
session.json   Session ID、创建时间、过期时间、metadata、事件版本
events.jsonl   每一行是一个 SessionEvent，顺序不能改变
```

关键方法的设计目的：

| 方法 | 目的 |
|---|---|
| `withSessionLock` | 同一 JVM 内，同一个 Session 的读写串行执行，避免覆盖 |
| `appendEvent` | 单事件幂等追加；重试相同事件 ID 不会重复 |
| `appendEvents` | 多事件一次写入；`expectedVersion` 不匹配时拒绝，防止并发压缩覆盖 |
| `appendEncodedEvents` | 通过官方 `SessionEventCodec` 编码并追加 JSONL |
| `readPersistedEvents` | 逐行恢复事件；若最后一行因崩溃而不完整，只截断最后一行 |
| `replaceFileAtomically` | 先写临时文件，再替换正式文件，避免出现半个 JSON |
| `findEventOwner` | 检查事件 ID 是否被其他 Session 使用 |

文件实现是单机学习方案：锁只保护当前 JVM，多个应用进程同时写同一目录仍不安全。多实例部署应使用 PostgreSQL。

## 历史记录读取

`SessionHistoryService` 提供：

- `getMessages(sessionId)`：完整聊天日志，适合展示历史记录。
- `getActiveMessages(sessionId)`：当前仍会发送给模型的上下文视图。
- `search(sessionId, keyword)`：在完整日志中搜索文本。
- `delete(sessionId)`：删除会话及事件。

## 扩展方式

- 自定义存储：实现 `SessionRepository`。
- 授权、审计、脱敏：实现 `SessionServiceDecorator`。
- 会话标题、标签、归档、用户列表：使用业务表，通过 `sessionId` 关联。
- 不修改 Spring AI 官方 `AI_SESSION_*` 表，也不复制 `JdbcSessionRepository`。

## 测试

不依赖 OpenAI 的持久化测试：

```bash
./mvnw -Dtest=FileSessionRepositoryContractTest,H2SessionRepositoryRestartTest test
```

## 如何观察 PostgreSQL 持久化

运行 `LoveAppTest` 可以触发真实 OpenAI 调用并将两轮对话写入 PostgreSQL：

```bash
./mvnw -Dtest=LoveAppTest test
```

测试里的 `sessionId` 当前使用随机 UUID，每次运行产生新 Session。适合验证“当前运行能保存”，不适合验证“重启后恢复同一个会话”。

测试完成后，可以在 IDEA Database Console 执行：

```sql
SELECT id, created_at, expires_at, metadata, event_version
FROM ai_session
ORDER BY created_at DESC
LIMIT 10;
```

查看某个 Session 的事件顺序：

```sql
SELECT seq, timestamp, event_type, message_type, message_content
FROM ai_session_event
WHERE session_id = '替换为查询到的 Session ID'
ORDER BY seq;
```

如果要手动验证重启恢复，应先固定一个 `sessionId`，完成第一轮对话，重启应用，再使用同一个 `sessionId` 发起第二轮。当前测试类使用随机 ID，因此每次运行都是新会话。
