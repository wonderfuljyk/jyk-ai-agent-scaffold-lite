# AI Agent 项目面试问答

> 基于 `jyk-ai-agent-scaffold-lite` 真实代码，逐题回答。

---

## 〇、项目亮点速览

### 原有架构亮点（项目自带）

| 亮点 | 代码位置 | 面试价值 |
|------|----------|----------|
| **DDD 六模块分层** | `api/` `domain/` `infrastructure/` `app/` `trigger/` `types/` | 证明有工程化思维，不是写脚本 |
| **策略树装配模式** | `AbstractArmorySupport` → `RootNode` → `AiApiNode` → `ChatModelNode` → `AgentNode` → `AgentWorkflowNode` → `RunnerNode` | 责任链+策略模式，Agent 装配过程可扩展、可插拔 |
| **YAML 声明式配置** | `application-dev.yml` 加载 `only-one-agent.yml`，`AiAgentConfigTableVO` 绑定 | Agent 不需要写死代码，改 YAML 即可配出新 Agent |
| **Spring 动态注册** | `AbstractArmorySupport.registerBean()` → `DefaultListableBeanFactory.registerSingleton()` | 启动时根据 YAML 动态注册 Agent Bean，运行时按 agentId 查找 |
| **MCP 三传输模式** | `SSEToolMcpCreateService` / `StdioToolMcpCreateService` / `LocalToolMcpCreateService` | 标准化工具协议，对接外部系统 |
| **Google ADK 编排引擎** | `ParallelAgentNode` / `SequentialAgentNode` / `LoopAgentNode` | 原生支持三种工作流拓扑，不是自己手写调度 |
| **MySpringAI 桥接** | `MySpringAI extends BaseLlm` | 把 Spring AI 的 ChatModel 适配到 Google ADK 的 BaseLlm 接口，两个框架无缝对接 |
| **插件机制** | `MyLogPlugin` / `MyTestPlugin` / `PrometheusMonitoringPlugin` | Agent 生命周期钩子，日志+指标可插拔 |
| **多模态消息体** | `ChatCommandEntity` 支持 `texts[]` + `files[]` + `inlineDatas[]` | 文本/图片/文件/Alan 统一承载 |

### 本次新增亮点

| 新增功能 | 代码位置 | 解决什么 |
|----------|----------|----------|
| Redis 会话持久化 | `SessionContextRepositoryImpl` | 重启不丢对话历史 |
| LLM 重试+降级+超时 | `LlmRetryHandler` + `MySpringAI` | API 不稳定时自动恢复 |
| 8 层安全防护 | JWT/RateLimit/Sanitization/Guardrails/Audit/CORS/Validation/Exception | 生产级安全基线 |
| Query 路由+Skills | `QueryRouterService` | 自动选择 Agent+Skill |
| RAG 检索 | `RagService` + `InMemoryVectorStore` | 知识库增强回答 |
| ADK Session 恢复+回放 | `ChatService.recoverAndRetry()` | 重启自动恢复上下文 |
| 流式 SSE 恢复 | `streamWithSessionRecovery` + `onErrorResumeNext` | 流式场景也不丢上下文 |
| 可观测性 | `AgentObservabilityService` | 调用量/成功率/耗时实时看 |
| 热更新 | `AgentHotReloadService` | 不重启更新 Agent |
| 并发控制 | `LlmConcurrencyLimiter` | Semaphore(5) 防 LLM API 过载 |
| 前端 | `login.html` + `index.html` | JWT 登录+流式对话+历史加载 |

### 一句话总结面试怎么说

> "这个项目基于 Google ADK + Spring AI，用 DDD 六层架构和策略树模式做 Agent 装配，YAML 声明式配置支持 Parallel/Sequential/Loop 三种多 Agent 编排。我在上面加了 Redis 滑动窗口会话持久化、JWT+限流+清洗+护栏的 8 层安全、LLM 指数退避重试+降级、Query 自动路由、TF-IDF 轻量 RAG、以及流式/非流式双路径的 Session 恢复机制。"

---

## 一、核心基础问题

### 1. 用户问题进入系统后，经过哪五个模块？

```
用户消息
  → ① JWT Filter + RateLimit Interceptor（认证+限流）
  → ② InputSanitizationService（XSS/SQLi 清洗）
  → ③ QueryRouterService（意图分类：闲聊/代码/研究 → 选择 Agent）
  → ④ ChatService → ADK Runner → MySpringAI（重试+超时+并发控制）→ LLM
  → ⑤ OutputGuardrailsService（PII 脱敏）→ 响应
```

**检索触发时机**：QueryRouter 判断意图后，如果是知识型问题，调用 `RagService.searchAsContext(namespace, query)` → TF-IDF 分词 → 内存向量存储检索 → 结果注入 System Prompt。非知识型（闲聊/代码生成）不触发检索。

**工具调用时机**：LLM 生成过程中，Google ADK 根据 LLM 返回的 `tool_call`，通过 MCP 协议（SSE/Stdio/Local）调用外部工具。`ChatModelNode.java` 在装配阶段注册 `ToolCallback[]`。

**异常兜底链路**：
```
LLM 超时 → CompletableFuture.get(30s) 超时抛异常
  → LlmRetryHandler 判断是否可重试(超时=可重试)
    → 指数退避重试 1s→2s→4s（最多3次）
      → 成功 → 返回结果
      → 重试耗尽 → LlmExhaustedException
        → FallbackResponseService 返回兜底文案
          "抱歉，AI 服务暂时不可用，请稍后重试"

Session 丢失 → IllegalArgumentException "Session not found"
  → ChatService.recoverAndRetry()
    → 从 Redis 读历史 → 创建新 ADK Session → appendEvent 回放
    → migrateRedisData 迁移数据 → 重试
```

**代码位置**：`LlmRetryHandler.java:45`、`MySpringAI.java:185`、`ChatService.java:113`

---

### 2. 面试官怎么判断"调 API" vs "做过系统"？

| 维度 | 仅调 API | 做过系统（本项目） |
|------|----------|-------------------|
| 会话持久化 | 无 | Redis List RPUSH+LTRIM，重启不丢 |
| LLM 容错 | 无 | 指数退避重试 + 降级兜底 + 超时控制 |
| 安全 | 无 | 8 层（JWT/限流/清洗/护栏/CORS/审计） |
| 多 Agent 编排 | 单次 prompt | YAML 声明式 + Parallel/Sequential/Loop 工作流 |
| 可观测性 | console.log | 调用次数/成功率/耗时实时指标 |
| Query 路由 | 无 | 关键词+正则自动路由到不同 Agent |
| RAG | 无 | TF-IDF 文档摄入+检索+上下文注入 |
| 流控 | 无 | Guava RateLimiter + Semaphore 并发控制 |

---

## 二、RAG 相关追问

### 1. Chunk 如何切割？如何评估召回效果？是否重排？

**Chunk 策略**：`RagService.chunkText()`，固定大小 500 字符，重叠 50 字符。代码位置：`RagService.java:91`

```
原文: "ABCDEFGHIJ..." (1000字)
Chunk1: [0..500]   "ABCDE..."
Chunk2: [450..950] "...FGHIJ"  ← 重叠50字符防止断句
```

**为什么不调 Embedding API**？项目定位是"演示级脚手架"，用 TF-IDF 分词向量化零外部依赖。生产环境应替换为 `text-embedding-3-small` 调 OpenAI API 或本地 BGE 模型。替换点：`EmbeddingService.embed()`，只需改一个方法。

**召回评估**：当前用 `cosineSimilarity > 0.05` 阈值过滤。生产环境应建立 ground-truth 评估集，计算 Precision@K / Recall@K。

**是否需要重排**：当前未做。生产环境加 Cross-Encoder Reranker（如 bge-reranker-v2-m3），对 Top-K 结果二次排序。代码扩展点：`InMemoryVectorStore.search()` 返回后加 `rerank()` 步骤。

### 2. RAG 答案不准确，排查维度？

1. **Chunk 过大/过小** → 调整 `CHUNK_SIZE`（当前 500）/ `CHUNK_OVERLAP`（当前 50）
2. **向量质量差** → TF-IDF 对语义理解弱，换 Embedding Model
3. **阈值过高/过低** → 调整 `similarity > 0.05`
4. **检索结果未注入** → 检查 `searchAsContext()` 是否正确拼入 System Prompt
5. **知识库本身缺失** → 检查 `ingestDocument` 是否完整摄入

---

## 三、多 Agent 相关追问

### 1. Agent 职责边界 + 调度机制

**边界划分**：YAML 声明式配置，`only-one-agent.yml`：

```yaml
agents:
  - name: RenewableEnergyResearcher
    instruction: "你是可再生能源研究员..."
    output-key: renewable_energy_result    # ← 输出契约
  - name: SynthesisAgent
    instruction: "整合 {renewable_energy_result} 和 {ev_technology_result}..."
                                            # ← 通过 {output-key} 引用上游输出
```

**调度者**：Google ADK 的 `AgentWorkflowNode` → `ParallelAgentNode` / `SequentialAgentNode` / `LoopAgentNode`，在 `AgentNode.doApply()` 中根据 YAML 的 `agent-workflows.type` 创建对应拓扑。

**代码位置**：`AgentNode.java:35`、`AgentWorkflowNode.java`、`only-one-agent.yml:60`

### 2. 中间状态存储 + 异常处理

**状态存储**：ADK 内部用 `Session.state(Map<String,Object>)` 在 Agent 间传递 `{output-key}` 值。持久化到 Redis 的是对话历史（`ConversationMessageVO` JSON），不是 ADK 内部状态。

**异常处理**：
- 单 Agent 异常 → 父 Workflow 感知 → 当前实现抛异常
- 改进方向：每个 Agent 输出包装 `{status:SUCCESS|FAILED, output, error}`，调度器根据 status 决定重试/跳过/降级

---

## 四、项目真实性与设计能力

### 1. 场景是否真实？为什么多 Agent？

本项目定位是**脚手架**——演示 AI Agent 系统的完整工程能力，不是特定业务系统。

多 Agent 的合理性：
- **并行研究**：3 个 Researcher 同时查不同方向，Synthesis Agent 合并，节省等待时间
- **顺序编排**：CodeWriter→Reviewer→Refactorer，每个 Agent 专注一个环节
- **循环优化**：CriticAgent + RefinerAgent 循环直到输出合格

普通对话确实不需要多 Agent，但**复杂任务需要分解**——这就是 Agent 编排的价值。

### 2. 数据流全链路

```
用户提问: "社区养老政策分析"
  → ① JWT Filter 验证身份
  → ② InputSanitization 清洗输入
  → ③ QueryRouterService.route("社区养老政策分析")
      → 匹配 RESEARCH_PATTERN → agentId=100002
      → 匹配 SKILL_BATTLE_PLAN? No → skill=null
  → ④ ChatService.createSession(100002, userId)
      → Redis 查映射 → 有则复用，无则创建 ADK Session
  → ⑤ 可选: RagService.searchAsContext("policy", query)
      → TF-IDF 分词 → 内存向量存储 Top-5 → 拼入上下文
  → ⑥ ADK Runner → ParallelAgent(3个Researcher并行)
      → MySpringAI → CompletableFuture(30s超时) → LLM
      → LlmRetryHandler 重试/降级
  → ⑦ SynthesisAgent 合并结果
  → ⑧ OutputGuardrailsService.filter(输出) → 脱敏
  → ⑨ Redis RPUSH 持久化 → 用户收到回复
```

### 3. 异常场景处理

| 场景 | 处理 |
|------|------|
| 检索不到信息 | `searchAsContext()` 返回空字符串，LLM 基于自身知识回答 |
| 模型输出"胡说" | OutputGuardrails 做 PII 脱敏；SchemaValidator 可校验输出格式；暂无事实性校验 |
| 工具调用失败 | `ToolFallbackHandler` 返回结构化错误 JSON 给 LLM，LLM 自主决策降级策略 |
| Session 丢失 | `recoverAndRetry` 自动重建 + Redis 历史回放 |
| LLM 超时 | CompletableFuture 30s 超时 → 重试 → 降级 |
| 并发过载 | `LlmConcurrencyLimiter` Semaphore(5) 阻塞等待 |

---

## 五、架构图与项目闭环

### 1. "第三模块"逻辑

如果架构图是五层流水线，第三层通常是**记忆与检索模块**。

容易混淆的点：
- **不是所有请求都检索** → Query 路由先判断要不要检索
- **不是只有一种检索** → 短期记忆(会话窗口) vs 长期记忆(向量检索) vs 知识库(RAG)
- **检索结果注入方式** → 拼 System Prompt（本项目）vs Tool Result vs 独立 Context

本项目的实现：
- 短期记忆：Redis List `LRANGE` 最近 20 条
- 长期记忆：Session Redis 映射
- 知识库：RagService TF-IDF 检索

### 2. 伪模块与数据缺失

**伪模块**：`infrastructure` 下除 `redis/` 和 `adapter/repository/` 以外的包均为空壳（`dao/`, `gateway/`, `port/`）。

**数据缺失**：ADK Session 状态未持久化（重启后需重建）；无 Token 用量统计；无用户行为分析。

**无兜底**：RAG 检索失败无 fallback（直接返回空）；Agent Workflow 某节点异常无部分结果返回。

### 3. Query 路由 vs 向量检索

| | Query 路由 | 向量检索 |
|---|-----------|---------|
| 职责 | 意图分类 → 选 Agent | 语义检索 → 找相关文档 |
| 触发 | 每次请求 | Router 判断需要时才触发 |
| 协议 | 同进程 Java 方法调用 | 同进程（当前）；生产环境可换 gRPC(Milvus) |
| 代码 | `QueryRouterService.java` | `EmbeddingService` + `InMemoryVectorStore` + `RagService` |

### 4. Agent 边界/数据流/状态

**边界**：YAML 中 `instruction` 定义角色边界，`output-key` 定义数据契约。

**数据流**：Agent 之间通过 `{output-key}` 模板变量传递。Parallel→Sequential 模式：3 个 Researcher 并行输出 → SynthesisAgent 通过 `{renewable_energy_result}` 等引用结果。

**状态**：ADK Session 内部 `ConcurrentMap<String,Object>` 存储。本项目额外通过 Redis 持久化对话历史，`SessionResult` 记录恢复后的有效 sessionId。

---

## 六、项目能力验证

### 1. 怎么证明项目真实落地？

能追问到代码层面：
- "你的 JWT 是怎么实现的？" → `JwtTokenService.java:25` HS256 签名
- "重试策略是什么？" → `LlmRetryHandler.java:45` 指数退避 1s→2s→4s
- "Redis key 怎么设计的？" → `agent:context:{userId}:{sessionId}` List + RPUSH+LTRIM+EXPIRE
- "Session 丢了怎么办？" → `ChatService.recoverAndRetry()` 自动重建+回放
- "流式 SSE 怎么做容错？" → `onErrorResumeNext` 响应式恢复

每个问题都能指到具体的 Java 文件和行号。

### 2. 画架构图的目的

检查闭环：每个模块的输入是什么、输出是什么、异常怎么处理。画图过程中发现的问题：Query 路由和 RAG 的衔接、ADK Session 丢失的恢复路径、流式和非流式的恢复不对称。

### 3. 省下的时间做什么？

不花时间画图 → 花时间验证每个模块的异常路径是否真的能走通。比如故意关 Redis 验证降级、改错 API Key 验证重试+降级、重启应用验证 Session 恢复+历史回放。

### 4. 面试怎么讲 Agent？

不要上来就说框架名。顺着用户的请求路径讲：

> "用户请求进来，先过 JWT 验证身份，然后我们做了一个 Query 路由——关键词匹配决定用哪个 Agent。比如代码类任务走 Sequential Pipeline，研究类走 Parallel Pipeline。路由完如果需要检索，调 RAG 服务从知识库拉相关文档拼到 Prompt 里。然后进 Agent 调度引擎——这里用了 Google ADK 的 Workflow 能力，支持 Parallel/Sequential/Loop 三种拓扑。LLM 调用的时候包了重试+超时+并发控制，失败有三层兜底。最后输出过一遍 PII 脱敏再返回。整个过程的数据通过 Redis List 存下来，支持滑动窗口上下文裁剪，重启也不丢。"
