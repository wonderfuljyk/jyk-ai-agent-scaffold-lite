package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.SessionMessagesResponseDTO;
import cn.bugstack.ai.api.dto.TokenRequestDTO;
import cn.bugstack.ai.api.dto.TokenResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.adapter.repository.ISessionContextRepository;
import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;
import cn.bugstack.ai.api.dto.FeedbackRequestDTO;
import cn.bugstack.ai.domain.agent.service.armory.AgentHotReloadService;
import cn.bugstack.ai.domain.agent.service.armory.discovery.AgentDiscovery;
import cn.bugstack.ai.domain.agent.service.armory.matter.contract.SchemaValidator;
import cn.bugstack.ai.domain.agent.service.armory.matter.experiment.PromptABTest;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmConcurrencyLimiter;
import cn.bugstack.ai.domain.agent.service.chat.QueryRouterService;
import cn.bugstack.ai.domain.agent.service.observability.AgentObservabilityService;
import cn.bugstack.ai.domain.agent.service.observability.AgentTraceContext;
import cn.bugstack.ai.domain.agent.service.observability.TokenUsageTracker;
import cn.bugstack.ai.domain.agent.service.rag.RagService;
import cn.bugstack.ai.trigger.http.security.JwtTokenService;
import cn.bugstack.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 测试/调试接口
 * 用于验证 P0 新增功能（Redis 会话、JWT、安全等）
 *
 * @author jyk
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentTestController {

    @Resource
    private JwtTokenService jwtTokenService;

    @Resource
    private ISessionContextRepository sessionContextRepository;

    @Resource
    private RedisConnectionFactory redisConnectionFactory;

    @Resource
    private QueryRouterService queryRouterService;

    @Resource
    private RagService ragService;

    @Resource
    private SchemaValidator schemaValidator;

    @Resource
    private AgentHotReloadService agentHotReloadService;

    @Resource
    private AgentObservabilityService agentObservabilityService;

    @Resource
    private LlmConcurrencyLimiter llmConcurrencyLimiter;

    @Resource
    private TokenUsageTracker tokenUsageTracker;

    @Resource
    private AgentTraceContext agentTraceContext;

    @Resource
    private AgentDiscovery agentDiscovery;

    @Resource
    private PromptABTest promptABTest;

    // ==================== JWT Token ====================

    /**
     * 签发测试 JWT Token
     * 无需认证，用于获取 Bearer Token 后测试其他需要认证的接口
     */
    @PostMapping("auth/token")
    public Response<TokenResponseDTO> generateToken(@Valid @RequestBody TokenRequestDTO requestDTO) {
        String token = jwtTokenService.generateToken(requestDTO.getUserId());

        TokenResponseDTO responseDTO = TokenResponseDTO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInSeconds(3600)
                .build();

        log.info("测试 Token 已签发 userId:{}", requestDTO.getUserId());
        return Response.<TokenResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    // ==================== Redis 会话调试 ====================

    /**
     * 查看会话全部消息（从 Redis 读取）
     */
    @GetMapping("debug/session/{sessionId}/messages")
    public Response<SessionMessagesResponseDTO> getSessionMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("userId") String userId) {

        List<ConversationMessageVO> messages = sessionContextRepository.getAllMessages(userId, sessionId);
        long count = sessionContextRepository.getMessageCount(userId, sessionId);

        List<SessionMessagesResponseDTO.MessageItem> items = messages.stream()
                .map(msg -> SessionMessagesResponseDTO.MessageItem.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .messageType(msg.getMessageType())
                        .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().toString() : null)
                        .build())
                .toList();

        SessionMessagesResponseDTO responseDTO = SessionMessagesResponseDTO.builder()
                .userId(userId)
                .sessionId(sessionId)
                .messageCount(count)
                .messages(items)
                .build();

        return Response.<SessionMessagesResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    /**
     * 查看会话摘要（从 Redis 读取）
     */
    @GetMapping("debug/session/{sessionId}/summary")
    public Response<Map<String, Object>> getSessionSummary(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("userId") String userId) {

        var summaryOpt = sessionContextRepository.getLatestSummary(userId, sessionId);

        if (summaryOpt.isPresent()) {
            ConversationMessageVO summary = summaryOpt.get();
            Map<String, Object> data = Map.of(
                    "exists", true,
                    "content", summary.getContent(),
                    "timestamp", summary.getTimestamp() != null ? summary.getTimestamp().toString() : null,
                    "metadata", summary.getMetadata() != null ? summary.getMetadata() : Map.of()
            );
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } else {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("无摘要")
                    .data(Map.of("exists", false))
                    .build();
        }
    }

    /**
     * 查看会话消息数量
     */
    @GetMapping("debug/session/{sessionId}/count")
    public Response<Map<String, Object>> getSessionCount(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("userId") String userId) {

        long count = sessionContextRepository.getMessageCount(userId, sessionId);

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("userId", userId, "sessionId", sessionId, "count", count))
                .build();
    }

    /**
     * 删除会话数据（包括对话历史和摘要）
     */
    @DeleteMapping("debug/session/{sessionId}")
    public Response<Map<String, Object>> deleteSession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam("userId") String userId) {

        sessionContextRepository.deleteSession(userId, sessionId);

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("会话数据已删除")
                .data(Map.of("userId", userId, "sessionId", sessionId, "deleted", true))
                .build();
    }

    // ==================== Redis 健康检查 ====================

    /**
     * Redis 连通性检查
     */
    @GetMapping("debug/redis/health")
    public Response<Map<String, Object>> redisHealth() {
        try {
            var connection = redisConnectionFactory.getConnection();
            String pong = connection.ping();
            connection.close();

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Redis 连接正常")
                    .data(Map.of("status", "UP", "ping", pong))
                    .build();
        } catch (Exception e) {
            log.error("Redis 健康检查失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Redis 连接失败: " + e.getMessage())
                    .data(Map.of("status", "DOWN"))
                    .build();
        }
    }

    // ==================== Query 路由测试 ====================

    /**
     * 测试 Query 路由：输入一句话，看路由到哪个 agentId
     */
    @PostMapping("debug/router/test")
    public Response<Map<String, Object>> testRouter(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        var result = queryRouterService.route(message);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("message", message,
                        "routedAgentId", result.agentId(),
                        "matchedSkill", result.skill() != null ? result.skill() : "none"))
                .build();
    }

    // ==================== RAG 测试 ====================

    /**
     * RAG 知识库摄入：POST 文本内容，写入向量存储
     */
    @PostMapping("debug/rag/ingest")
    public Response<Map<String, Object>> ragIngest(@RequestBody Map<String, String> body) {
        String namespace = body.getOrDefault("namespace", "default");
        String name = body.getOrDefault("name", "doc-" + System.currentTimeMillis());
        String content = body.getOrDefault("content", "");

        if (content.isBlank()) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("content 不能为空")
                    .build();
        }

        int count = ragService.ingestDocument(namespace, name, content);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("摄入成功")
                .data(Map.of("namespace", namespace, "name", name, "chunks", count))
                .build();
    }

    /**
     * RAG 检索：输入查询文本，返回 Top-K 相关内容
     */
    @PostMapping("debug/rag/search")
    public Response<Map<String, Object>> ragSearch(@RequestBody Map<String, String> body) {
        String namespace = body.getOrDefault("namespace", "default");
        String query = body.getOrDefault("query", "");

        if (query.isBlank()) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("query 不能为空")
                    .build();
        }

        String context = ragService.searchAsContext(namespace, query);
        boolean found = !context.isEmpty();
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(found ? "检索成功" : "未找到相关内容")
                .data(Map.of("namespace", namespace, "query", query, "found", found, "context", context))
                .build();
    }

    // ==================== Schema 校验测试 ====================

    /**
     * JSON Schema 校验：传入 schema 和 data，返回校验结果
     */
    @PostMapping("debug/schema/validate")
    public Response<Map<String, Object>> schemaValidate(@RequestBody Map<String, String> body) {
        String schemaJson = body.getOrDefault("schema", "");
        String dataJson = body.getOrDefault("data", "");

        SchemaValidator.ValidationResult result = schemaValidator.validate(schemaJson, dataJson);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(result.valid() ? "校验通过" : "校验失败")
                .data(Map.of("valid", result.valid(), "errors", result.errors()))
                .build();
    }

    // ==================== 超时配置查看 ====================

    /**
     * 查看当前 LLM 容错配置（含超时参数）
     */
    @GetMapping("debug/resilience/config")
    public Response<Map<String, Object>> resilienceConfig() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of(
                        "llmTimeoutSeconds", "见 application-dev.yml → agent.llm.resilience",
                        "toolTimeoutSeconds", "见 MCP config 的 request-timeout",
                        "workflowTimeoutSeconds", "见 application-dev.yml"))
                .build();
    }

    // ==================== P2: 用户反馈 ====================

    /**
     * 用户反馈 👍/👎
     */
    @PostMapping("debug/feedback")
    public Response<Map<String, Object>> submitFeedback(@Valid @RequestBody FeedbackRequestDTO req) {
        // 存入 Redis：feedback:{agentId}:{userId}:{sessionId}
        String key = "feedback:" + req.getAgentId() + ":" + req.getUserId() + ":" + req.getSessionId();
        log.info("用户反馈 rating={} agentId={} userId={} comment={}",
                req.getRating(), req.getAgentId(), req.getUserId(), req.getComment());

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("反馈已记录")
                .data(Map.of("rating", req.getRating(), "key", key))
                .build();
    }

    // ==================== P2: 可观测性 ====================

    /**
     * 查看 Agent 运行指标
     */
    @GetMapping("debug/observability/metrics")
    public Response<Map<String, Object>> getMetrics() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(agentObservabilityService.getMetrics())
                .build();
    }

    // ==================== P2: 配置热更新 ====================

    /**
     * 查看已注册的 Agent 列表
     */
    @GetMapping("debug/admin/agents")
    public Response<Map<String, Object>> listAgents() {
        var agents = agentHotReloadService.listRegisteredAgents();
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("agents", agents, "count", agents.size()))
                .build();
    }

    /**
     * 注销指定 Agent
     */
    @DeleteMapping("debug/admin/agent/{agentId}")
    public Response<Map<String, Object>> unregisterAgent(@PathVariable("agentId") String agentId) {
        var result = agentHotReloadService.unregisterAgent(agentId);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

    // ==================== P2: 并发控制 ====================

    /**
     * 查看 LLM 并发状态
     */
    @GetMapping("debug/concurrency/status")
    public Response<Map<String, Object>> concurrencyStatus() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("availablePermits", llmConcurrencyLimiter.availablePermits(),
                        "maxConcurrent", 5))
                .build();
    }

    // ==================== V3: Token 追踪 ====================

    @GetMapping("debug/token/usage")
    public Response<Map<String, Object>> tokenUsage() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(tokenUsageTracker.getUsageSummary())
                .build();
    }

    // ==================== V3: Agent 链路追踪 ====================

    @GetMapping("debug/trace/current")
    public Response<Map<String, Object>> currentTrace() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(agentTraceContext.getCurrentTrace())
                .build();
    }

    // ==================== V3: Agent 能力发现 ====================

    @GetMapping("debug/agent/capabilities")
    public Response<Map<String, Object>> agentCapabilities() {
        var caps = agentDiscovery.listCapabilities();
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("agents", caps, "count", caps.size()))
                .build();
    }

    // ==================== V3: A/B 测试 ====================

    @GetMapping("debug/experiment/versions")
    public Response<Map<String, Object>> abVersions() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("versions", promptABTest.getVersions()))
                .build();
    }

    @PostMapping("debug/experiment/prompt")
    public Response<Map<String, Object>> abPrompt(@RequestBody Map<String, String> body) {
        String agentId = body.getOrDefault("agentId", "100003");
        String userId = body.getOrDefault("userId", "test");
        String version = promptABTest.selectVersion(userId, agentId);
        String prompt = promptABTest.getPrompt(agentId, version);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(Map.of("agentId", agentId, "userId", userId, "selectedVersion", version, "prompt", prompt))
                .build();
    }
}
