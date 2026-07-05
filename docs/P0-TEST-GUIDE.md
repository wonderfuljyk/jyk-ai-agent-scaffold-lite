# P0 功能测试指南

> 本地 Redis 已启动（端口 6379），应用启动后即可按本文档逐项验证。

---

## 接口总览

| # | 方法 | 路径 | 用途 | 需 Token |
|---|------|------|------|----------|
| 1 | POST | /api/v1/auth/token | 签发 JWT | 否 |
| 2 | GET | /api/v1/debug/redis/health | Redis 连通检查 | 否 |
| 3 | POST | /api/v1/chat | 发聊天消息 | **是** |
| 4 | GET | /api/v1/debug/session/{id}/messages | 查 Redis 会话消息 | 否 |
| 5 | GET | /api/v1/debug/session/{id}/count | 查消息数量 | 否 |
| 6 | GET | /api/v1/debug/session/{id}/summary | 查摘要 | 否 |
| 7 | DELETE | /api/v1/debug/session/{id} | 删除会话 | 否 |
| 8 | POST | /api/v1/chat（无 Token） | 验 JWT 拦截 | — |
| 9 | POST | /api/v1/chat（空 agentId） | 验 DTO 校验 | — |

---

## 1. 签发测试 Token

```bash
curl -s -X POST http://localhost:8091/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"test-user"}' | python3 -m json.tool
```

预期返回：
```json
{
  "code": "0000",
  "info": "成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresInSeconds": 3600
  }
}
```

**记下 token 值**，后续步骤用它设置环境变量：
```bash
TOKEN="<上面返回的 token>"
```

---

## 2. Redis 健康检查

```bash
curl -s http://localhost:8091/api/v1/debug/redis/health | python3 -m json.tool
```

预期：
```json
{"code": "0000", "info": "Redis 连接正常", "data": {"status": "UP", "ping": "PONG"}}
```

---

## 3. 验证会话持久化（核心 P0-1）

### 3.1 发一条消息

```bash
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"agentId":"100003","userId":"test-user","message":"你好，请用一句话回复我"}' | python3 -m json.tool
```

记下返回结果，然后获取 sessionId（调用 create_session 或用同样的 agentId+userId 组合对应的 sessionId）。

### 3.2 先获取 sessionId

如果不知道 sessionId，创建一个：
```bash
curl -s -X POST http://localhost:8091/api/v1/create_session \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"agentId":"100003","userId":"test-user"}' | python3 -m json.tool
```

记下 `sessionId`：
```bash
SID="<返回的 sessionId>"
```

### 3.3 查看 Redis 中的消息数量

```bash
curl -s "http://localhost:8091/api/v1/debug/session/$SID/count?userId=test-user" | python3 -m json.tool
```

预期 `count >= 2`（至少 1 条 user + 1 条 assistant）。

### 3.4 查看 Redis 中的消息列表

```bash
curl -s "http://localhost:8091/api/v1/debug/session/$SID/messages?userId=test-user" | python3 -m json.tool
```

预期看到类似：
```json
{
  "code": "0000",
  "data": {
    "userId": "test-user",
    "sessionId": "xxx",
    "messageCount": 4,
    "messages": [
      {"role": "system", "content": "Session created...", "messageType": "query"},
      {"role": "user", "content": "你好...", "messageType": "query"},
      {"role": "assistant", "content": "你好！...", "messageType": "reply"}
    ]
  }
}
```

### 3.5 验证 LTRIM 滑动窗口（发 >20 条）

```bash
for i in $(seq 1 22); do
  curl -s -X POST http://localhost:8091/api/v1/chat \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"agentId\":\"100003\",\"userId\":\"test-user\",\"sessionId\":\"$SID\",\"message\":\"第${i}条消息\"}" > /dev/null
  echo "发送第 $i 条"
done

# 验证最多只保留 20 条
curl -s "http://localhost:8091/api/v1/debug/session/$SID/count?userId=test-user" | python3 -m json.tool
```

预期 `count <= 20`（窗口限制）。

### 3.6 验证摘要生成（发 >10 条触发）

发完上面 22 条后，等 1-2 秒（异步摘要），然后：
```bash
curl -s "http://localhost:8091/api/v1/debug/session/$SID/summary?userId=test-user" | python3 -m json.tool
```

如果有摘要，返回：
```json
{"code": "0000", "data": {"exists": true, "content": "[对话摘要] 此前对话要点：...", ...}}
```

---

## 4. 验证 JWT 认证拦截（核心 P0-3）

### 4.1 不带 Token → 401

```bash
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"agentId":"100003","userId":"test-user","message":"hi"}' | python3 -m json.tool
```

预期：
```json
{"code": "E0401", "info": "缺少或无效的 Authorization 头"}
```

### 4.2 带有效 Token → 正常

```bash
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"agentId":"100003","userId":"test-user","message":"hi"}' | python3 -m json.tool
```

预期正常返回 `code: "0000"`。

### 4.3 白名单路径无需 Token

```bash
# 查询配置列表（白名单）→ 正常
curl -s http://localhost:8091/api/v1/query_ai_agent_config_list | python3 -m json.tool

# Token 签发（白名单）→ 正常
curl -s -X POST http://localhost:8091/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"another-user"}'
```

---

## 5. 验证 DTO 参数校验（核心 P0-3）

```bash
# 空 agentId
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"agentId":"","userId":"test","message":"hi"}' | python3 -m json.tool
```

预期：
```json
{"code": "0002", "info": "agentId: agentId 不能为空"}
```

```bash
# 消息超长（>10000 字符）
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"agentId\":\"100003\",\"userId\":\"test\",\"message\":\"$(python3 -c 'print("x"*10001)')\"}"
```

预期返回 `code: "0002"`。

---

## 6. 验证 LLM 重试与降级（核心 P0-2）

### 6.1 准备：临时改错 API Key

修改 `application-dev.yml` 中 agent 配置的 `api-key` 为一个无效值（比如 `sk-invalid-test-key`），重启应用。

### 6.2 发消息触发重试

```bash
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"agentId":"100003","userId":"test-user","message":"你好"}' | python3 -m json.tool
```

检查应用日志，应出现：
```
WARN  LLM 调用重试中 model:xxx attempt:1/3 backoff:1000ms error:...
WARN  LLM 调用重试中 model:xxx attempt:2/3 backoff:2000ms error:...
WARN  LLM 调用重试中 model:xxx attempt:3/3 backoff:4000ms error:...
ERROR LLM 调用失败(重试耗尽) model:xxx ...
ERROR LLM 调用重试耗尽，触发降级 model:xxx
WARN  返回降级兜底回复 model:xxx
```

接口返回的 content 是兜底文案 `"抱歉，AI 服务暂时不可用，请稍后重试。"`。

### 6.3 恢复：改回正确的 API Key，重启应用

---

## 7. 验证输出护栏（核心 P0-3）

如果 LLM 正常，这条通过后会自动验证——Controller 层对 LLM 输出调用了 `OutputGuardrailsService.filter()`。如果 LLM 返回的内容包含手机号/身份证号/邮箱，会自动脱敏。

也可以直接看应用日志确认脱敏行为：
```
INFO  输出中脱敏 1 个手机号
INFO  输出中脱敏 1 个邮箱
```

---

## 8. 审计日志

任意发一个请求后，查应用日志：
```bash
grep "AUDIT" /export/Logs/ai-agent-scaffold-lite-boot/app.log
```

预期格式：
```
INFO  AUDIT | user=test-user | ip=127.0.0.1 | POST /api/v1/chat | status=SUCCESS | duration=1234ms
```

---

## 9. 清理测试数据

```bash
curl -s -X DELETE "http://localhost:8091/api/v1/debug/session/$SID?userId=test-user" | python3 -m json.tool
```

验证已删除：
```bash
curl -s "http://localhost:8091/api/v1/debug/session/$SID/count?userId=test-user"
# → count: 0
```

---

## 测试清单

| 序号 | 测试项 | P0 关联 | 验证方式 |
|------|--------|---------|----------|
| 1 | Redis 连通 | P0-4 | `/debug/redis/health` 返回 PONG |
| 2 | 消息持久化 | P0-1 | 发消息后 `/debug/session/{id}/messages` 有数据 |
| 3 | LTRIM 窗口 | P0-1 | 发 >20 条后 count ≤ 20 |
| 4 | 摘要生成 | P0-1 | 发 >10 条后 `/debug/session/{id}/summary` exists=true |
| 5 | JWT 拦截 | P0-3 | 无 Token → 401 |
| 6 | JWT 通过 | P0-3 | 有效 Token → 正常 |
| 7 | 白名单放行 | P0-3 | `/auth/token` `/query_ai_agent_config_list` `/debug/*` 无 Token 可访问 |
| 8 | DTO 校验 | P0-3 | 空 agentId → code=0002 |
| 9 | LLM 重试 | P0-2 | 错 Key → 3 次 WARN + 兜底回复 |
| 10 | LLM 降级 | P0-2 | 重试耗尽 → 返回兜底文案 |
| 11 | 输出脱敏 | P0-3 | LLM 输出含手机号 → 日志显示脱敏 |
| 12 | 审计日志 | P0-3 | 日志含 `AUDIT` 行 |
| 13 | 会话删除 | P0-1 | DELETE 后 count=0 |
