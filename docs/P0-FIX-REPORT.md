# P0/P1 修复报告

> 项目：jyk-ai-agent-scaffold-lite  
> 日期：2026-07-04  
> 编译：BUILD SUCCESS（全部 7 个 Maven 模块）

---

## 一、总览

| 优先级 | 编号 | 功能 | 新建文件 | 修改文件 |
|--------|------|------|----------|----------|
| P0 | 1 | 会话持久化（Redis RPUSH） | 6 | 4 |
| P0 | 2 | LLM 重试与降级 | 4 | 3 |
| P0 | 3 | 完整安全防护 | 9 | 7 |
| P0 | 4 | 基础设施层（Redis） | 2 | 4 |
| P1 | 6 | Query 路由 | 1 | 0 |
| P1 | 7 | RAG 向量检索 | 3 | 0 |
| P1 | 8 | Agent 类型契约 | 1 | 0 |
| P1 | 9 | 超时控制 | 0 | 3 |
| P1 | 10 | 工具调用降级 | 1 | 0 |
| — | — | 前端页面 | 0 | 2 |
| — | — | 测试接口 + HTTP 文件 | 4 | 2 |
| — | — | Bug 修复 | 0 | 6 |

**合计**：新建 31 个文件，修改 31 个文件。

---

## 二、P0 修复详情

### P0-1：会话持久化 — Redis RPUSH 滑动窗口

**设计**：
```
Key: agent:context:{userId}:{sessionId}  (Redis List)
操作: RPUSH → LTRIM -20 -1 → EXPIRE 7200
摘要: agent:summary:{userId}:{sessionId}  (独立 Key)
映射: agent:session:{agentId}:{userId}   (会话复用)
```

**新建文件**：
- `ConversationMessageVO.java` — 消息值对象
- `ISessionContextRepository.java` — DDD 仓储接口（12 个方法）
- `SessionContextRepositoryImpl.java` — StringRedisTemplate + 手动 JSON 实现
- `SessionRedisProperties.java` — TTL/窗口/摘要配置
- `SessionSummaryService.java` — 异步摘要服务（LLEN>10 触发）

**关键特性**：
- 每次 RPUSH 自动 LTRIM 保留最近 20 条
- 每次写入刷新 TTL（活跃用户永不过期）
- 摘要存独立 Key，不受 LTRIM 裁剪
- 会话映射实现 agentId+userId → sessionId 复用
- ADK Session 重启丢失时自动回放历史到新 Session

---

### P0-2：LLM 重试与降级

**容错链**：
```
LLM 调用 → 可重试错误(超时/429/5xx) → 指数退避重试(1s→2s→4s, 最多3次)
       → 不可重试错误(401/400) → 立即失败
       → 重试耗尽 → LlmExhaustedException → 兜底文案
```

**新建文件**：
- `LlmResilienceProperties.java` — retry/backoff/circuit-breaker 配置
- `LlmRetryHandler.java` — 错误分类 + 指数退避
- `LlmExhaustedException.java` — 重试耗尽异常
- `FallbackResponseService.java` — 兜底文案

**修改**：
- `MySpringAI.java` — setter 注入重试/降级/超时，generateFallbackResponse
- `AgentNode.java` — 装配时注入容错依赖
- `application-dev.yml` — 容错参数配置

---

### P0-3：完整安全防护（8 层）

```
① DTO 校验（@Valid + Jakarta Validation）
② JWT 认证（jjwt 签发/验证 + OncePerRequestFilter）
③ 限流（Guava RateLimiter，每用户 10 QPS）
④ 输入清洗（XSS/SQLi/敏感词过滤）
⑤ 输出护栏（手机号/身份证/邮箱 PII 脱敏）
⑥ 审计日志（AOP 切面：userId/IP/method/path/duration）
⑦ CORS 收敛（白名单替代 @CrossOrigin("*")）
⑧ 全局异常处理（@RestControllerAdvice）
```

**新建文件**：
- `InputSanitizationService.java` / `OutputGuardrailsService.java`
- `JwtTokenService.java` / `JwtAuthenticationFilter.java`
- `RateLimitInterceptor.java`
- `GlobalExceptionHandler.java`
- `AuditLogAspect.java`
- `SecurityFilterConfig.java` / `WebMvcConfig.java`

**修改**：
- `ChatRequestDTO.java` / `CreateSessionRequestDTO.java` — @NotBlank/@Size/@Pattern
- `AgentServiceController.java` — @Valid + 安全集成 + 移除 try-catch
- `ChatService.java` — InputSanitization 纵深防御
- `ResponseCode.java` — E0003/E0401/E0429

---

### P0-4：基础设施层（Redis）

- `RedisConfig.java` — StringRedisTemplate + ObjectMapper（JavaTimeModule）
- `application-dev.yml` — Redis 连接 + 会话配置

---

## 三、P1 修复详情

### P1-6：Query 路由

`QueryRouterService.java` — 关键词正则匹配分类意图：
| 意图 | 关键词 | 路由 Agent |
|------|--------|-----------|
| 闲聊 | 你好/hi/谢谢 | 100003（通用） |
| 代码 | 写/编写/代码/算法 | 100001（Sequential Pipeline） |
| 研究 | 研究/分析/调研 | 100002（Parallel Pipeline） |
| 默认 | — | 100003 |

---

### P1-7：RAG 检索增强

**无外部依赖**的轻量 RAG：
- `EmbeddingService.java` — TF-IDF 分词 + 余弦相似度
- `InMemoryVectorStore.java` — 内存向量存储（分 namespace）
- `RagService.java` — 文档摄入 → 分块 → 向量化 → 检索 → 上下文注入

---

### P1-8：Agent 类型契约

`SchemaValidator.java` — JSON Schema 运行时校验（type/required/properties）

---

### P1-9：超时控制

- `LlmResilienceProperties` 加 `llmTimeoutSeconds`(30s) / `toolTimeoutSeconds`(10s) / `workflowTimeoutSeconds`(120s)
- `MySpringAI.java` — `CompletableFuture.get(timeout)` 包裹 LLM 调用

---

### P1-10：工具调用降级

`ToolFallbackHandler.java` — 工具异常 → 结构化 JSON 错误 → LLM 自主决策

---

## 四、Bug 修复

| 问题 | 修复 |
|------|------|
| JWT Filter 双注册导致 /auth/token 被拦截 | 去掉 @Component，改用 SecurityFilterConfig 手动创建 Bean |
| AntPathMatcher 不匹配 debug 路径 | 换回 startsWith 前缀匹配 |
| @PathVariable/@RequestParam 反射失败 | 加显式 name 属性 |
| GenericJackson2JsonRedisSerializer 类型推断失败 | 改用 StringRedisTemplate + 手动 JSON |
| ConcurrentHashMap 删除后会话不复用 | Redis 加 sessionId 映射，createSession 先查后建 |
| ADK Session 重启丢失 | recoverAndRetry 自动重建 + Redis 历史回放 |

---

## 五、前端页面

### login.html
- 去掉硬编码 admin/admin
- 输入 userId → 调 `/api/v1/auth/token` → Cookie 存 `{user, token}`
- 自动跳转 index.html

### index.html
- 从 Cookie 读 token（不重复签发）
- localStorage 持久化 sessionId（key=`ai_agent_session_{agentId}_{userId}`）
- 页面加载 + 切换智能体时自动调 `/debug/session/{id}/messages` 加载历史
- 流式对话复用已有会话

---

## 六、测试接口

### 新增 debug 端点（AgentTestController）

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | /auth/token | 签发 JWT |
| GET | /debug/redis/health | Redis PING |
| GET | /debug/session/{id}/messages | 查看会话消息 |
| GET | /debug/session/{id}/count | 消息数量 |
| GET | /debug/session/{id}/summary | 会话摘要 |
| DELETE | /debug/session/{id} | 删除会话 |
| POST | /debug/router/test | Query 路由测试 |
| POST | /debug/rag/ingest | RAG 文档摄入 |
| POST | /debug/rag/search | RAG 检索 |
| POST | /debug/schema/validate | Schema 校验 |

### HTTP 测试文件
`docs/p0-test.http` — 19 个步骤，覆盖 P0+P1 全部功能

---

## 七、关键架构决策

1. **Redis 值用纯 JSON 字符串**，不用 GenericJackson2JsonRedisSerializer — 避免类型推断不可靠
2. **摘要独立 Key** — 不受 LTRIM 裁剪
3. **会话映射独立 Key** — 实现 agent+user → sessionId 复用
4. **MySpringAI 用 setter 注入**扩展功能 — 不破坏已有 8 个构造函数
5. **安全清理在 Controller + Service 双防护** — 纵深防御
6. **RAG 用 TF-IDF** 而非 embedding API — 零外部依赖
7. **LLM 超时用 CompletableFuture.get(timeout)** — 不侵入 Spring AI 调用链
