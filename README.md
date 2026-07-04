# AI Agent 系统 Remediation 分析报告

> 基于 `jyk-ai-agent-scaffold-lite` 代码库的全面审查
> 审查日期：2026-07-04

---

## 一、核心基础问题分析

### 1.1 用户问题进入系统后的五模块流程

**当前实际请求链路**：

```
HTTP Request → AgentServiceController → ChatService → Armory(Agent组装) → Google ADK Runner → LLM
```

**理想完整五模块流程**：

```
① 网关/安全层 → ② Query路由/意图识别 → ③ 记忆/上下文检索 → ④ Agent调度执行 → ⑤ 工具调用/RAG检索 → LLM生成 → 后处理
```

| 模块 | 当前状态 | 状态说明 |
|------|----------|----------|
| ① 安全/网关 | ❌ 完全缺失 | 无鉴权、无限流、无输入校验、无内容护栏 |
| ② Query 路由 | ❌ 完全缺失 | 无意图分类，agentId 由客户端硬编码指定 |
| ③ 记忆/检索 | ❌ 完全缺失 | 无向量数据库、无 RAG、无长期记忆、无 embedding |
| ④ Agent 调度 | ✅ 部分存在 | Google ADK 提供基础 workflow 编排 |
| ⑤ 工具调用 | ✅ 部分存在 | MCP 协议三种传输模式已实现 |

**检索操作触发时机**：当前系统从未触发检索。理论上应在「Query 路由判断需要检索」之后、「LLM 调用」之前触发。

**工具调用触发时机**：LLM 生成过程中，框架根据 LLM 返回的 `tool_call` 自动通过 MCP 协议调用外部工具。

**执行失败兜底分析**：

当前仅有三层 try-catch（`ChatService` 和 `AgentServiceController`）：

```java
// 当前兜底 - 严重不足
try {
    // 业务逻辑
} catch (AppException e) {
    return Response.error(e.getCode(), e.getInfo());  // 仅返回错误码，无上下文
} catch (Exception e) {
    return Response.error(UN_ERROR);  // 丢失异常堆栈，无法排查
}
```

**缺失的兜底机制**：
- LLM 调用无重试、无备用模型降级、无兜底回复
- 工具调用失败后 LLM 无法感知，直接抛异常
- Agent workflow 中某个子 Agent 失败，整个流程中断，无部分结果
- 会话丢失后无恢复机制

---

### 1.2 "调用 API" vs "做过完整系统" 判断维度

| 维度 | 仅调 API | 做过完整系统 | 当前代码状态 |
|------|----------|-------------|-------------|
| Agent 编排 | 单次 prompt | 多 Agent 协作、DAG 调度、条件路由 | ⚠️ Google ADK workflow（但仅 YAML 静态配置） |
| 工具系统 | 硬编码函数调用 | MCP 协议、工具注册/发现、沙箱执行 | ✅ MCP 三种传输模式 |
| 记忆系统 | 无 | 短期记忆(会话) + 长期记忆(向量库) + 工作记忆 | ❌ 仅 ConcurrentHashMap（重启即丢失） |
| 状态管理 | 无状态 | 检查点、断点续跑、状态持久化、状态版本控制 | ❌ 仅内存，无 checkpoint |
| 可观测性 | console.log | 链路追踪、指标面板、审计日志、评估报告 | ⚠️ Prometheus 插件骨架存在但未完善 |
| 安全防护 | 无 | 输入/输出护栏、越狱检测、PII 脱敏、权限控制 | ❌ 完全缺失 |
| 评估体系 | 无 | 离线评估集、在线指标监控、人工反馈闭环 | ❌ 完全缺失 |
| 错误处理 | try-catch | 重试(指数退避)、降级(备用模型)、熔断、兜底文案 | ❌ 仅基础 try-catch |
| 配置管理 | 硬编码 | 声明式配置、热更新、A/B 实验、灰度发布 | ⚠️ YAML 声明式但无热更新 |
| 多模态 | 仅文本 | 图片/文件/音频统一处理 pipeline | ⚠️ ChatCommandEntity 已定义但未深度集成 |

**核心结论**：当前代码在 Agent 编排和工具系统上有亮点，但在生产化能力（持久化、容错、安全、评估）上存在系统性缺失。面试官会从这些缺口判断候选人的实战深度。

---

## 二、架构图中的关键问题

### 2.1 "第三模块"逻辑 — 记忆/上下文检索

如果你画的架构图是五模块流水线，第三模块通常是**记忆/上下文检索模块**，这是最容易产生混淆的环节。

**混淆点 1：什么时候触发检索？**

```
错误理解：用户问题来了 → 直接检索
正确逻辑：用户问题 → Query 路由判断意图 → 决定是否需要检索 → 需要才检索
```

关键判断逻辑（当前代码完全没有）：
- 闲聊/打招呼 → 不需要检索
- 事实性问答 → 需要知识库检索
- 个性化问题 → 需要历史记忆检索
- 工具调用 → 不需要检索（LLM 会自己调工具）

**混淆点 2：检索什么？三种检索，策略不同**

| 检索类型 | 存储内容 | 检索方式 | 当前状态 |
|----------|----------|----------|----------|
| 短期记忆 | 当前会话对话历史 | 时间窗口截断（最近 N 轮） | ❌ 存在内存但无截断策略 |
| 长期记忆 | 用户历史对话摘要/关键信息 | 向量相似度检索 | ❌ 完全缺失 |
| 知识库 RAG | 文档 chunk → embedding | 向量相似度 + 重排序 | ❌ 完全缺失 |

**混淆点 3：检索结果如何注入 LLM？**

- 拼到 System Prompt 里 → 增加 token 消耗但简单
- 作为 Tool Result 返回 → LLM 可以决定用不用
- 作为独立 Context 注入 → 需要框架支持 context 分离

当前代码中 `ChatCommandEntity` 只有 `texts/files/inlineDatas`，没有 `contexts/retrievedDocuments` 字段。

---

### 2.2 伪模块清单

| 模块路径 | 实际情况 | 风险 |
|----------|----------|------|
| `ai-agent-scaffold-lite-infrastructure/` 全部子包 | 仅有 `package-info.java` | 架构图上有，实际不存在 |
| `infrastructure/dao/` | 空包 | 无数据访问层 |
| `infrastructure/dao/po/` | 空包 | 无持久化对象定义 |
| `infrastructure/adapter/port/` | 空包 | 无外部端口适配 |
| `infrastructure/adapter/repository/` | 空包 | 无仓库实现 |
| `infrastructure/gateway/` | 空包 | 无外部网关 |
| `infrastructure/redis/` | 空包 | 无缓存实现 |
| `domain/business/` | 空包 | 业务领域逻辑缺失 |
| `trigger/job/` | 空包 | 无定时任务 |
| `trigger/listener/` | 空包 | 无事件监听 |

**本质上 infrastructure 和 domain/business 两大模块是"架构图上的模块，代码里的空壳"。**

---

### 2.3 数据缺失环节

| 缺失的数据 | 位置 | 影响 |
|-----------|------|------|
| 对话历史持久化 | `ChatService.userSessions` 仅内存 | 服务重启全部丢失 |
| Agent 执行链路 | 无存储 | 无法回溯排查、无法审计、无法评估 |
| 工具调用日志 | 无记录 | 工具调用失败无法排查 |
| 用户反馈 | 无收集端点 | 无法做 RLHF 或效果评估 |
| 数据库 Schema | infrastructure 全空 | 无数据模型定义 |
| LLM Token 用量 | 未统计 | 无成本核算 |

---

### 2.4 无兜底方案的环节

| 环节 | 文件位置 | 风险场景 | 应有兜底 |
|------|----------|----------|----------|
| LLM API 调用 | `MySpringAI.java` | 超时/限流/返回异常 | 指数退避重试 + 备用模型降级 + 兜底回复 |
| MCP 工具调用 | `TooMcpCreateService.java` 子类 | 远程工具不可用/超时 | 返回结构化错误给 LLM，让 LLM 自主降级 |
| Agent workflow | `LoopAgentNode/ParallelAgentNode/SequentialAgentNode` | 某子 Agent 卡死 | 单 Agent 超时中断 + 返回已完成的部分结果 |
| Session 查询 | `ChatService.java` | sessionId 不存在 | 明确错误码 + 引导客户端重新创建会话 |
| YAML 配置加载 | `AiAgentAutoConfig.java` | 配置格式错误 | 启动时 JSON Schema 校验 + 详细错误定位 |
| Agent 查找 | `ChatService.java` | agentId 不在注册表中 | 返回可用 Agent 列表供选择 |

---

### 2.5 Query 路由和向量检索 — 职责与协议设计

#### Query 路由模块（当前不存在，设计如下）

**职责**：
- 意图分类：闲聊 / 知识问答 / 工具调用 / 复杂多 Agent 任务
- 复杂度评估：简单 → 单 Agent，复杂 → 多 Agent workflow
- 敏感内容预检：涉政/涉黄/涉暴 → 拒绝或标记
- 路由决策：输出目标 `agentId` + 是否需要检索 + 预处理后的 query

**内部协议**（同进程 Java 方法调用）：

```
Trigger → QueryRouter: RouteRequest { query, sessionContext }
QueryRouter → MemoryRetrieval: RetrieveRequest { query, topK, filters }
QueryRouter → AgentScheduler: ScheduleRequest { agentId, query, retrievedContext }
```

#### 向量检索模块（当前不存在，设计如下）

**职责**：
- 用户问题 → embedding 向量化
- 向量相似度检索（知识库文档 / 历史对话 / Few-shot 示例）
- 检索结果重排序（Reranker）
- 返回 Top-K 相关文档及相关性分数

**外部协议**（跨进程）：

```
MemoryRetrieval → Embedding Service: HTTP (OpenAI-compatible /v1/embeddings)
MemoryRetrieval → VectorDB (Milvus/Qdrant): gRPC / HTTP REST
```

---

### 2.6 Agent 实现细节 — 边界/数据流/状态

> 注意：当前代码使用的是 **Google ADK**，不是 LangGraph。但核心概念相通。

#### Agent 边界划分

当前通过 **YAML 声明 + output-key** 划分：

```yaml
agents:
  - name: RenewableEnergyResearcher
    instruction: "你是可再生能源研究员..."    # 边界：角色 + 任务范围
    output-key: renewable_energy_result       # 边界：输出契约
  - name: SynthesisAgent
    instruction: "整合 {renewable_energy_result} 和 {ev_technology_result}..."
```

**问题**：
- Agent 输入/输出契约**仅靠自然语言约定**，无结构化 schema 校验
- `{output-key}` 是字符串模板替换，**类型不安全**
- 上游 Agent 输出格式不符合下游预期时，**下游直接拿到乱数据**

**改进方向**：每个 Agent 定义 `input_schema` 和 `output_schema`（JSON Schema），运行时校验。

#### 数据流转路径

```
YAML Config
  → AiAgentAutoConfig (监听 ApplicationReadyEvent)
    → RootNode (入口)
      → AiApiNode (构建 OpenAiApi)
        → ChatModelNode (构建 ChatModel + 绑定 MCP tools / Skills)
          → AgentNode (创建 LlmAgent)
            → AgentWorkflowNode (构建 workflow 拓扑)
              → LoopAgentNode / ParallelAgentNode / SequentialAgentNode
                → AgentWorkflowNode (递归处理下一 workflow)
                  → RunnerNode (创建 InMemoryRunner, 注册为 Spring Bean)
                    → ChatService 查找 Bean
                      → runner.runAsync(userId, sessionId, content)
                        → Flowable<Event> → 收集文本输出
```

**缺失环节**：
- 无显式的 typed state（LangGraph 有 `StateGraph(OverallState)`）
- 中间结果仅由 ADK 框架内部持有，外部无法拦截/观测
- 无数据血缘追踪

#### 中间状态存储

当前实现：**Google ADK `InMemoryRunner` + `SessionService`**，全部 JVM 堆内存。

```
InMemoryRunner
  └── SessionService (ConcurrentHashMap)
       └── Session
            ├── events: List<Event>           (事件流，无限增长)
            ├── state: Map<String, Object>    (Agent 间传递的临时状态)
            └── messages: List<Message>       (对话历史)
```

**致命问题**：
- 🚫 服务重启 → 所有会话丢失
- 🚫 无 checkpoint → 无法断点续跑
- 🚫 内存无限增长 → 无 TTL、无驱逐策略
- 🚫 无持久化 → 无法事后审计和分析

---

## 三、多 Agent 相关追问

### 3.1 多 Agent 职责边界与调度机制

**当前实现**：

- **职责边界**：通过 YAML 中每个 Agent 的 `name` + `instruction` + `output-key` 划分
- **调度者**：Google ADK 的 `LoopAgent` / `ParallelAgent` / `SequentialAgent`，由 `AgentWorkflowNode` 根据配置创建
- **调度策略**：完全静态 — YAML 写死 workflow 拓扑，无运行时动态调度

**缺失的能力**：
- 无运行时动态路由（无法根据中间结果决定下一步调用哪个 Agent）
- 无条件分支（LangGraph 的 `add_conditional_edges`）
- 无循环控制（loop 有最大次数但无法根据条件跳出）
- 无优先级调度

### 3.2 中间状态存储与异常处理

**状态存储**：如 2.6 所述，完全内存，无持久化。

**异常处理**：
- 当前：子 Agent 异常 → 整个 workflow 中断 → 异常上抛到 Controller
- 应有：子 Agent 异常 → 标记该节点失败 → 通知调度器 → 决策（重试/跳过/降级/终止）

**改进方向**：
```
每个 Agent 执行结果包装为：
{
  status: SUCCESS | FAILED | TIMEOUT,
  output: "...",
  error: { type: "...", message: "..." },
  metadata: { duration: 1234, tokens: 500 }
}

Scheduler 根据 status 决定：
- SUCCESS → 继续下一个 Agent
- FAILED → 重试(最多3次) → 仍失败 → 降级或终止
- TIMEOUT → 跳过该 Agent，继续 workflow（带标记）
```

---

## 四、Remediation 改进清单

### 🔴 P0 — 致命缺陷（生产不可用）

| # | 问题 | 当前代码位置 | 改进方案 |
|---|------|-------------|----------|
| 1 | **状态无持久化** | `ChatService.userSessions` ConcurrentHashMap | 引入 Redis 存储 session + 数据库存储 checkpoint；实现断点续跑 |
| 2 | **LLM 调用无容错** | `MySpringAI.java` 直接调用无重试 | 指数退避重试(3次) + 备用模型降级 + 兜底回复文案 |
| 3 | **安全防护全空** | 无相关代码 | 输入层：敏感词过滤；输出层：PII 脱敏 + 越狱检测 |
| 4 | **infrastructure 全空壳** | 6个子包仅有 package-info | 实现 DAO/Repository/Gateway/Redis；至少先上 Redis 做会话缓存 |
| 5 | **API Key 明文硬编码** | YAML 配置文件中 `api-key` 字段 | 迁移到环境变量/Spring 加密配置/Vault；立即轮换已泄露的 Key |

### 🟠 P1 — 严重影响（核心功能缺失）

| # | 问题 | 当前代码位置 | 改进方案 |
|---|------|-------------|----------|
| 6 | **无 Query 路由** | agentId 由客户端在 URL 中指定 | 增加意图分类器（轻量模型或规则引擎），自动路由到合适 Agent |
| 7 | **无 RAG/向量检索** | 无 embedding、无向量库 | 引入向量数据库(Milvus/Qdrant)，实现文档入库→embedding→检索→上下文注入完整 pipeline |
| 8 | **Agent 间无类型契约** | YAML 中仅靠 `{output-key}` 模板 | 定义 Agent 输入/输出 JSON Schema，运行时校验 + 类型转换 |
| 9 | **无超时控制** | Agent 可能无限执行 | LLM 调用 30s、工具调用 10s、整体 workflow 120s；超时后优雅降级 |
| 10 | **工具调用无降级** | MCP 工具失败直接抛异常 | 失败后构造结构化错误返回给 LLM，让 LLM 自主决策降级策略 |

### 🟡 P2 — 重要改善

| # | 问题 | 当前代码位置 | 改进方案 |
|---|------|-------------|----------|
| 11 | **可观测性不足** | `PrometheusMonitoringPlugin` 仅骨架 | OpenTelemetry 全链路 trace + 工具调用日志 + Agent 耗时分布面板 |
| 12 | **无评估体系** | 无任何 eval 代码 | 构建评估数据集 → 离线评估(准确率/工具调用正确率)→ 在线指标监控 |
| 13 | **无用户反馈闭环** | 无反馈 API | 增加 👍/👎 反馈端点，数据存入 DB 用于后续优化 |
| 14 | **配置无热更新** | YAML 在 `ApplicationReadyEvent` 一次性加载 | 支持运行时动态注册/注销 Agent，不重启更新配置 |
| 15 | **无流控/限流** | 无任何保护 | API 层令牌桶限流 + LLM 调用并发控制(信号量) |

### 🟢 P3 — 锦上添花

| # | 问题 | 当前代码位置 | 改进方案 |
|---|------|-------------|----------|
| 16 | **多模态未充分利用** | `ChatCommandEntity` 定义完善但使用不完整 | 完善 image/audio 处理 pipeline，增加文件类型自动检测 |
| 17 | **Skills 系统集成浅** | 有 battle-plan/pdf skills 但仅作 tool 加载 | Skills 可作为 Agent 的动态知识注入，按意图自动选择 Skill |
| 18 | **无 A/B 测试** | 无 | 同一 Agent 多版本配置，按流量比例灰度路由 |
| 19 | **MyBatis 依赖冗余** | pom.xml 声明但数据源注释掉 | 清理无用依赖，或正式实现数据库层 |
| 20 | **三框架共存** | Google ADK + Spring AI + LangChain4j | 收敛到一套主框架(推荐 Google ADK + Spring AI)，减少依赖冲突风险 |

---

## 五、总结

### 做得好的
- **Agent 编排架构**：策略树模式 + YAML 声明式配置，扩展性设计良好
- **MCP 协议支持**：SSE/Stdio/Local 三种传输，工具生态标准化
- **多 Agent workflow**：parallel/sequential/loop 三种拓扑，覆盖常见场景
- **模块分层**：api/trigger/domain/infrastructure DDD 分层清晰

### 本质问题
> 当前 scaffold 处于 **"Agent Demo"** 阶段，距 **"AI Agent 系统"** 还差关键的生产化能力：**持久化、容错、安全、评估、可观测**。这五个维度恰恰是面试官区分"调过 API"和"做过系统"的核心分水岭。

### 面试应对要点
1. **不要说"我用 XX 框架搭了个 Agent"** — 这等于告诉面试官你只调了 API
2. **要说你解决了什么生产问题** — 状态持久化怎么做？LLM 超时怎么处理？工具失败了怎么兜底？
3. **画架构图时要诚实** — 伪模块要标注为"规划中"，不要画成已实现
4. **准备具体数字** — P99 延迟多少？工具调用成功率多少？Token 成本多少？
