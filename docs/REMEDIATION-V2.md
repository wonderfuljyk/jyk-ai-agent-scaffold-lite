# jyk-ai-agent-scaffold-lite 功能手册

> 基于 `cn.bugstack.ai:ai-agent-scaffold-lite:1.0` 的全量改造  
> 日期：2026-07-04  
> Java 17 · Spring Boot 3.4.3 · Google ADK 0.5.0 · Spring AI 1.1.0-M3

---

## 一、架构总览

```
┌─────────────────────────────────────────────────────────┐
│  前端 (Nginx 静态页面)                                    │
│  login.html → JWT 登录 → index.html (流式对话+历史加载)    │
└──────────────┬──────────────────────────────────────────┘
               │ HTTP (JWT + CORS + 限流)
┌──────────────▼──────────────────────────────────────────┐
│  Trigger 层 (Controller)                                 │
│  AgentServiceController  /  AgentTestController          │
│  安全: JwtAuthFilter → RateLimitInterceptor → @Valid     │
│  审计: AuditLogAspect                                    │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│  Domain 层 (核心业务)                                     │
│  ┌─────────┐ ┌──────────┐ ┌─────────┐ ┌─────────────┐  │
│  │Query路由 │ │RAG 检索   │ │Schema   │ │ LLM 容错     │  │
│  │+Skills  │ │(TF-IDF)  │ │校验     │ │ 重试+降级+超时│  │
│  └─────────┘ └──────────┘ └─────────┘ └─────────────┘  │
│  ┌──────────────────────────────────────────────────┐   │
│  │ ChatService: 会话管理 + 持久化 + ADK 恢复          │   │
│  │ SessionSummaryService: 异步摘要                    │   │
│  │ AgentObservabilityService: 指标采集                │   │
│  │ AgentHotReloadService: 热更新                      │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 安全: InputSanitization / OutputGuardrails         │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────┬──────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│  Infrastructure 层                                       │
│  Redis: StringRedisTemplate + 手动 JSON 序列化           │
│  VectorStore: 内存 TF-IDF 向量存储                       │
└──────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│  Google ADK 引擎                                         │
│  InMemoryRunner → LlmAgent → MySpringAI → LLM            │
│  Workflow: Sequential / Parallel / Loop                  │
│  MCP Tools: SSE / Stdio / Local                          │
└──────────────────────────────────────────────────────────┘
```

---

## 二、改造前 vs 改造后

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 会话存储 | `ConcurrentHashMap` 内存 | Redis List RPUSH 滑动窗口 |
| 会话复用 | 无 | Redis 映射 `agent:session:{agentId}:{userId}` |
| ADK 恢复 | 重启报错 | 自动重建 + 历史回放 |
| 安全 | 0 层 | 8 层（JWT+限流+清洗+护栏+审计+CORS+校验+异常） |
| LLM 容错 | 无 | 重试(1s→2s→4s) + 降级兜底 + 30s 超时 |
| Query 路由 | 客户端指定 agentId | 关键词自动路由 + Skill 匹配 |
| RAG | 无 | TF-IDF 文档摄入 + 检索 + 上下文注入 |
| 可观测性 | 无 | LLM 指标 + 审计日志 + 并发状态 |
| 前端 | 硬编码登录，刷新丢会话 | JWT 登录，localStorage 持久化，自动加载历史 |
| 依赖 | 4 框架混用 | Google ADK + Spring AI 双框架 |

---

## 三、新增功能清单与代码位置

### P0-1：会话持久化（Redis 滑动窗口）

| 文件 | 位置 | 功能 |
|------|------|------|
| `ConversationMessageVO.java` | `domain/.../model/valobj/` | 消息值对象（role/content/messageType/timestamp/metadata） |
| `ISessionContextRepository.java` | `domain/.../adapter/repository/` | DDD 仓储接口：appendMessage/RPUSH、getRecentMessages/LRANGE、saveSummary/LPUSH、getSessionId 等 |
| `SessionContextRepositoryImpl.java` | `infrastructure/.../adapter/repository/` | **核心实现**：RPUSH + LTRIM -20 -1 + EXPIRE 7200，StringRedisTemplate 手动 JSON，try-catch 兜底 |
| `SessionRedisProperties.java` | `domain/.../valobj/properties/` | 配置：ttlSeconds/maxTurns/summaryThreshold |
| `SessionSummaryService.java` | `domain/.../service/chat/` | 异步摘要：LLEN>10 → 异步线程 → LPUSH |
| `RedisConfig.java` | `infrastructure/.../redis/` | StringRedisTemplate + ObjectMapper(JavaTimeModule) |

**Redis Key 体系**：
| Key | 类型 | 用途 |
|-----|------|------|
| `agent:context:{userId}:{sessionId}` | List | 对话历史，RPUSH+LTRIM |
| `agent:summary:{userId}:{sessionId}` | Value | 摘要，独立 Key 不受 LTRIM 裁剪 |
| `agent:session:{agentId}:{userId}` | Value | 会话映射，实现复用 |

**修改文件**：`ChatService.java`（集成持久化+恢复+回放）、`application-dev.yml`

---

### P0-2：LLM 重试与降级

| 文件 | 位置 | 功能 |
|------|------|------|
| `LlmResilienceProperties.java` | `domain/.../valobj/properties/` | maxRetries=3, backoff=1s→2s→4s, fallbackReply, timeout |
| `LlmRetryHandler.java` | `domain/.../matter/resilience/` | 错误分类（可重试/不可重试）+ 指数退避 |
| `LlmExhaustedException.java` | `domain/.../matter/resilience/` | 重试耗尽信号 |
| `FallbackResponseService.java` | `domain/.../matter/fallback/` | 兜底文案 |

**错误分类**：
- 可重试：`SocketTimeoutException`, `ConnectException`, HTTP 429/5xx
- 不可重试：HTTP 401/400

**修改文件**：`MySpringAI.java`（setter 注入 + CompletableFuture 超时 + generateFallbackResponse）、`AgentNode.java`（装配注入）

---

### P0-3：完整安全防护（8 层）

| 层级 | 文件 | 位置 | 功能 |
|------|------|------|------|
| ① DTO 校验 | `ChatRequestDTO.java` / `CreateSessionRequestDTO.java` | `api/.../dto/` | `@NotBlank @Size @Pattern` |
| ② JWT 认证 | `JwtTokenService.java` | `trigger/.../security/` | jjwt HS256 签发/验证 |
| | `JwtAuthenticationFilter.java` | `trigger/.../security/` | OncePerRequestFilter，排除白名单+OPTIONS，`shouldNotFilter` |
| ③ 限流 | `RateLimitInterceptor.java` | `trigger/.../security/` | Guava RateLimiter 10 QPS |
| ④ 输入清洗 | `InputSanitizationService.java` | `domain/.../service/security/` | XSS 过滤、SQLi 检测、敏感词拦截 |
| ⑤ 输出护栏 | `OutputGuardrailsService.java` | `domain/.../service/security/` | 手机号/身份证/邮箱 PII 脱敏 |
| ⑥ 审计日志 | `AuditLogAspect.java` | `trigger/.../audit/` | AOP 切面：userId/IP/method/path/status/duration |
| ⑦ CORS | `WebMvcConfig.java` | `app/.../config/` | 白名单收敛 |
| ⑧ 全局异常 | `GlobalExceptionHandler.java` | `trigger/.../http/` | `@RestControllerAdvice` 统一处理 |

**修改文件**：`AgentServiceController.java`、`ChatService.java`、`ResponseCode.java`、`SecurityFilterConfig.java`、`app/pom.xml`、`application-dev.yml`

---

### P1-6：Query 路由 + Skills 匹配

| 文件 | 位置 | 功能 |
|------|------|------|
| `QueryRouterService.java` | `domain/.../service/chat/` | 关键词正则 → RouteResult(agentId, skill) |

**路由规则**：
| 意图 | 关键词 | 路由 |
|------|--------|------|
| 闲聊 | 你好/hi/谢谢 | 100003（通用 Agent） |
| 代码 | 写/代码/算法/重构 | 100001（Sequential Code Pipeline） |
| 研究 | 研究/分析/调研 | 100002（Parallel Research Pipeline） |
| Skill:battle-plan | 性能/清理/磁盘/CPU | 100003 + skill=battle-plan |
| Skill:pdf | PDF/表单/提取/识别 | 100003 + skill=pdf |

---

### P1-7：RAG 检索增强

| 文件 | 位置 | 功能 |
|------|------|------|
| `EmbeddingService.java` | `domain/.../service/rag/` | TF-IDF 分词向量化 + 余弦相似度 |
| `InMemoryVectorStore.java` | `domain/.../service/rag/` | 内存向量存储，namespace 分区，similarity>0.05 过滤 |
| `RagService.java` | `domain/.../service/rag/` | 文档摄入(chunk 500字)→ 向量化 → 检索(Top-5) → searchAsContext() |

**特点**：零外部依赖，不调 embedding API，适合小规模知识库（<1万条）。

---

### P1-8：Agent 类型契约

| 文件 | 位置 | 功能 |
|------|------|------|
| `SchemaValidator.java` | `domain/.../matter/contract/` | JSON Schema 校验：type/required/properties，返回 ValidationResult |

---

### P1-9：超时控制

| 文件 | 修改 | 功能 |
|------|------|------|
| `LlmResilienceProperties.java` | 新增 llmTimeoutSeconds(30)/toolTimeoutSeconds(10)/workflowTimeoutSeconds(120) | 超时配置 |
| `MySpringAI.java` | `CompletableFuture.supplyAsync().get(timeoutSec)` 包裹 LLM 调用 | 超时即抛异常 → 进入重试/降级链 |

---

### P1-10：工具调用降级

| 文件 | 位置 | 功能 |
|------|------|------|
| `ToolFallbackHandler.java` | `domain/.../matter/resilience/` | 工具异常 → 结构化 JSON 错误 → LLM 自主决策 |

---

### P2-11：可观测性

| 文件 | 位置 | 功能 |
|------|------|------|
| `AgentObservabilityService.java` | `domain/.../service/observability/` | LLM 调用次数/成功率/平均耗时 实时指标；工具调用计数 |

**集成**：`MySpringAI.java` 每完成一次 LLM 调用自动记录

---

### P2-13：用户反馈闭环

| 文件 | 位置 | 功能 |
|------|------|------|
| `FeedbackRequestDTO.java` | `api/.../dto/` | agentId/userId/sessionId/query/reply/rating/comment |
| `AgentTestController.java` | `trigger/.../http/` | POST `/debug/feedback` 接收 👍/👎 |

---

### P2-14：配置热更新

| 文件 | 位置 | 功能 |
|------|------|------|
| `AgentHotReloadService.java` | `domain/.../service/armory/` | reloadAll（重新加载）、unregisterAgent（注销）、listRegisteredAgents（列表） |

---

### P2-15：LLM 并发控制

| 文件 | 位置 | 功能 |
|------|------|------|
| `LlmConcurrencyLimiter.java` | `domain/.../matter/resilience/` | Semaphore(5) 信号量，MySpringAI 调用前 acquire，finally 中 release |

---

### P3-16：多模态增强

**修改文件**：`ChatCommandEntity.java`
- `File.autoDetectType()` — 根据扩展名推断 image/audio/video/document
- `InlineData.detectedType()` — 根据 MIME 类型推断

---

### P3-17：Skills 深度集成

**修改文件**：`QueryRouterService.java`
- 新增 `SKILL_BATTLE_PLAN` / `SKILL_PDF` 正则匹配
- `RouteResult` 包含 `agentId` + `skill` 双字段

---

### P3-19/20：依赖精简

**清理**：
- 删除 LangChain4j 所有依赖（`langchain4j`, `langchain4j-core`, `langchain4j-open-ai`, `langchain4j-bom`, `google-adk-contrib-langchain4j`）
- 删除 MyBatis + MySQL 依赖
- 删除 2 个 LangChain4j 测试文件
- 保留 Google ADK + Spring AI 双框架

---

## 四、前端

| 文件 | 路径 | 功能 |
|------|------|------|
| `login.html` | `docs/dev-ops/nginx/html/` | JWT 登录：输入 userId → `/auth/token` → Cookie+localStorage → 跳转 |
| `index.html` | `docs/dev-ops/nginx/html/` | 流式对话：`createSession` → `chat_stream` SSE；localStorage 持久化 sessionId；页面加载自动调 `/debug/session/{id}/messages` 恢复历史 |

---

## 五、测试工具

### Debug 端点（`AgentTestController.java`）

| 端点 | 用途 |
|------|------|
| `POST /auth/token` | 签发 JWT |
| `GET /debug/redis/health` | Redis PING |
| `GET /debug/session/{id}/count` | 消息数量 |
| `GET /debug/session/{id}/messages` | 消息列表 |
| `GET /debug/session/{id}/summary` | 会话摘要 |
| `DELETE /debug/session/{id}` | 删除会话 |
| `POST /debug/router/test` | Query 路由测试 |
| `POST /debug/rag/ingest` | RAG 文档摄入 |
| `POST /debug/rag/search` | RAG 检索 |
| `POST /debug/schema/validate` | Schema 校验 |
| `POST /debug/feedback` | 用户反馈 |
| `GET /debug/observability/metrics` | 可观测性指标 |
| `GET /debug/admin/agents` | 已注册 Agent 列表 |
| `DELETE /debug/admin/agent/{id}` | 注销 Agent |
| `GET /debug/concurrency/status` | 并发状态 |
| `GET /debug/resilience/config` | 容错配置 |

### HTTP 测试文件
`docs/p0-test.http` — 32 步完整测试流程

---

## 六、ADK Session 恢复流程

```
用户请求(旧 sessionId)
  → createSession 验证 ADK 有效性
    ├─ ADK 存活 → 返回旧 sessionId ✓
    └─ ADK 丢失(重启) → 创建新 sessionId
         → migrateRedisData(旧→新)
         → 更新 Redis 映射
         → 返回新 sessionId
  → 非流式: runWithSessionRecovery (try-catch + recoverAndRetry + 历史回放)
  → 流式: streamWithSessionRecovery (onErrorResumeNext + 重建+重试)
```

---

## 七、关键设计决策

1. **Redis 值用纯 JSON 字符串** — 避免 `GenericJackson2JsonRedisSerializer` 类型推断不可靠
2. **摘要独立 Key** — 不受 LTRIM 裁剪
3. **MySpringAI 用 setter 注入** — 不破坏已有 8 个构造函数
4. **安全清理双防护** — Controller + Service 两层
5. **RAG 用 TF-IDF** — 零外部嵌入模型依赖
6. **流式恢复用 onErrorResumeNext** — RxJava 响应式错误处理，不是 try-catch
7. **前端每次发消息都调 createSession** — 后端验证 ADK 有效性，避免 sessionId 漂移
