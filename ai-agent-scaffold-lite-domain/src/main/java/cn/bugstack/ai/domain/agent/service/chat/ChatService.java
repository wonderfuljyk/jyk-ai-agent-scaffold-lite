package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.adapter.repository.ISessionContextRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.security.InputSanitizationService;
import cn.bugstack.ai.domain.agent.service.security.OutputGuardrailsService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class ChatService implements IChatService {

    /** 恢复时回放的最大历史消息条数 */
    private static final int RECOVERY_REPLAY_COUNT = 20;

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private ISessionContextRepository sessionContextRepository;

    @Resource
    private SessionSummaryService sessionSummaryService;

    @Resource
    private InputSanitizationService inputSanitizationService;

    @Resource
    private OutputGuardrailsService outputGuardrailsService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.armory.matter.resilience.AdkSessionPersistence adkSessionPersistence;

    @Resource
    private cn.bugstack.ai.domain.agent.service.armory.matter.resilience.RequestDeduplicator requestDeduplicator;

    @Resource
    private cn.bugstack.ai.domain.agent.service.observability.AgentTraceContext agentTraceContext;

    // ==================== 公开接口 ====================

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo) {
                    agentList.add(vo.getAgent());
                }
            }
        }
        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == vo) throw new AppException(ResponseCode.E0001.getCode());

        Optional<String> existing = sessionContextRepository.getSessionId(agentId, userId);
        if (existing.isPresent()) {
            String sid = existing.get();
            // 验证 ADK session 是否仍存在（重启后内存 Session 会丢失）
            try {
                var sessionOpt = vo.getRunner().sessionService()
                        .getSession(vo.getAppName(), userId, sid,
                                java.util.Optional.of(GetSessionConfig.builder().build()))
                        .blockingGet();
                if (sessionOpt != null) {
                    log.info("复用已有会话 agentId:{} userId:{} sessionId:{}", agentId, userId, sid);
                    return sid;
                }
            } catch (Exception e) {
                log.warn("ADK Session 验证失败，将创建新会话: {}", e.getMessage());
            }
            // ADK session 不存在，创建新的
            log.info("ADK Session 已失效，创建新会话 agentId:{} userId:{}", agentId, userId);
        }
        return createNewAdkSession(vo, agentId, userId);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        String sanitized = inputSanitizationService.sanitize(message);
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == vo) throw new AppException(ResponseCode.E0001.getCode());
        String sessionId = createSession(agentId, userId);
        return handleMessage(agentId, userId, sessionId, sanitized);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == vo) throw new AppException(ResponseCode.E0001.getCode());

        // V3: 请求去重
        if (requestDeduplicator.isDuplicate(userId, sessionId, message)) {
            log.info("重复请求已拦截 userId={} sessionId={}", userId, sessionId);
            return List.of("(重复请求，已跳过 LLM 调用)");
        }

        // 持久化用户消息
        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("user").content(message).messageType("query")
                        .timestamp(LocalDateTime.now()).build());

        // 执行（带恢复）+ 链路追踪
        InMemoryRunner runner = vo.getRunner();
        Content userMsg = Content.fromParts(Part.fromText(message));
        agentTraceContext.startAgent("ChatService");
        agentTraceContext.startAgent(agentId);
        SessionResult result;
        try {
            result = runWithSessionRecovery(runner, agentId, userId, sessionId, userMsg);
        } finally {
            agentTraceContext.endAgent(agentId);
            agentTraceContext.endAgent("ChatService");
            agentTraceContext.remove();
        }

        // 持久化 assistant 回复（用恢复后的有效 sessionId）
        String effectiveSid = result.effectiveSessionId();
        String fullReply = String.join("\n", result.outputs());
        sessionContextRepository.appendMessage(userId, effectiveSid,
                ConversationMessageVO.builder()
                        .role("assistant").content(fullReply).messageType("reply")
                        .timestamp(LocalDateTime.now()).build());

        sessionSummaryService.checkAndSummarizeAsync(userId, effectiveSid);
        return result.outputs();
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == vo) throw new AppException(ResponseCode.E0001.getCode());

        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("user").content(message).messageType("query")
                        .timestamp(LocalDateTime.now()).build());

        return streamWithSessionRecovery(vo, agentId, userId, sessionId, message);
    }

    private Flowable<Event> streamWithSessionRecovery(AiAgentRegisterVO vo, String agentId,
            String userId, String sessionId, String message) {
        InMemoryRunner runner = vo.getRunner();
        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig config = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();

        return runner.runAsync(userId, sessionId, userMsg, config)
                .doOnComplete(() -> sessionSummaryService.checkAndSummarizeAsync(userId, sessionId))
                .onErrorResumeNext(throwable -> {
                    if (throwable instanceof IllegalArgumentException
                            && throwable.getMessage() != null
                            && throwable.getMessage().contains("Session not found")) {
                        log.warn("流式 ADK Session 丢失，重建中 agentId:{} userId:{}", agentId, userId);
                        String newSid = createNewAdkSession(vo, agentId, userId);
                        migrateRedisData(userId, sessionId, newSid);
                        return runner.runAsync(userId, newSid, userMsg, config)
                                .doOnComplete(() -> sessionSummaryService.checkAndSummarizeAsync(userId, newSid));
                    }
                    return Flowable.error(throwable);
                });
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity entity) {
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(entity.getAgentId());
        if (null == vo) throw new AppException(ResponseCode.E0001.getCode());

        List<Part> parts = new ArrayList<>();
        List<ChatCommandEntity.Content.Text> texts = entity.getTexts();
        for (ChatCommandEntity.Content.Text text : texts) {
            parts.add(Part.fromText(text.getMessage()));
        }
        List<ChatCommandEntity.Content.File> files = entity.getFiles();
        for (ChatCommandEntity.Content.File file : files) {
            parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
        }
        List<ChatCommandEntity.Content.InlineData> inlineDatas = entity.getInlineDatas();
        for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
            parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
        }
        Content content = Content.builder().role("user").parts(parts).build();

        String userMsgText = texts.stream()
                .map(ChatCommandEntity.Content.Text::getMessage)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        sessionContextRepository.appendMessage(entity.getUserId(), entity.getSessionId(),
                ConversationMessageVO.builder()
                        .role("user").content(userMsgText).messageType("query")
                        .timestamp(LocalDateTime.now())
                        .metadata(Map.of("file_count", files.size(), "inline_count", inlineDatas.size()))
                        .build());

        InMemoryRunner runner = vo.getRunner();
        SessionResult result = runWithSessionRecovery(runner, entity.getAgentId(),
                entity.getUserId(), entity.getSessionId(), content);

        String effectiveSid = result.effectiveSessionId();
        String fullReply = String.join("\n", result.outputs());
        sessionContextRepository.appendMessage(entity.getUserId(), effectiveSid,
                ConversationMessageVO.builder()
                        .role("assistant").content(fullReply).messageType("reply")
                        .timestamp(LocalDateTime.now()).build());

        sessionSummaryService.checkAndSummarizeAsync(entity.getUserId(), effectiveSid);
        return result.outputs();
    }

    // ==================== 会话生命周期 ====================

    private String createNewAdkSession(AiAgentRegisterVO vo, String agentId, String userId) {
        String appName = vo.getAppName();
        InMemoryRunner runner = vo.getRunner();

        Session session = runner.sessionService().createSession(appName, userId).blockingGet();
        String sessionId = session.id();

        sessionContextRepository.saveSessionMapping(agentId, userId, sessionId);
        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("system").content("Session created for agent: " + agentId)
                        .messageType("query").timestamp(LocalDateTime.now()).build());

        // V3: 持久化 ADK Session 状态到 Redis
        try {
            adkSessionPersistence.saveState(sessionId, session.state());
        } catch (Exception e) {
            log.warn("ADK Session 状态持久化失败(非阻塞): {}", e.getMessage());
        }
        log.info("ADK 会话已创建 agentId:{} userId:{} sessionId:{}", agentId, userId, sessionId);
        return sessionId;
    }

    // ==================== Session 恢复 ====================

    /** runAsync 结果 + 有效 sessionId */
    private record SessionResult(List<String> outputs, String effectiveSessionId) {}

    private SessionResult runWithSessionRecovery(InMemoryRunner runner, String agentId,
            String userId, String sessionId, Content content) {
        try {
            Flowable<Event> events = runner.runAsync(userId, sessionId, content);
            List<String> outputs = new ArrayList<>();
            events.blockingForEach(event -> outputs.add(event.stringifyContent()));
            return new SessionResult(outputs, sessionId);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("Session not found")) {
                log.warn("ADK Session 丢失，从 Redis 恢复 agentId:{} userId:{} oldSid:{}",
                        agentId, userId, sessionId);
                return recoverAndRetry(runner, agentId, userId, sessionId, content);
            }
            throw e;
        }
    }

    /**
     * 核心恢复逻辑：
     * 1. 从 Redis 读旧 session 的历史消息
     * 2. 创建新 ADK Session
     * 3. 逐条 appendEvent 注入历史（不触发 LLM）
     * 4. 迁移 Redis 数据到新 sessionId
     * 5. 执行当前用户消息
     */
    private SessionResult recoverAndRetry(InMemoryRunner runner, String agentId,
            String userId, String oldSessionId, Content currentContent) {
        AiAgentRegisterVO vo = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (vo == null) throw new AppException(ResponseCode.E0001.getCode());

        // 1. 从 Redis 读旧 session 的历史消息（跳过 system 消息和 summary）
        List<ConversationMessageVO> history = sessionContextRepository
                .getRecentMessages(userId, oldSessionId, RECOVERY_REPLAY_COUNT);
        List<ConversationMessageVO> replayMessages = history.stream()
                .filter(m -> !"system".equals(m.getRole()) && !"summary".equals(m.getMessageType()))
                .toList();

        // 2. 创建新 ADK Session
        String appName = vo.getAppName();
        Session newSession = runner.sessionService().createSession(appName, userId).blockingGet();
        String newSessionId = newSession.id();

        log.info("开始回放 {} 条历史消息到新 Session {} (共 {} 条历史)",
                replayMessages.size(), newSessionId, history.size());

        // 3. 逐条 appendEvent 注入历史
        int replayed = 0;
        for (ConversationMessageVO msg : replayMessages) {
            try {
                String author = "user".equals(msg.getRole()) ? "user" : "model";
                Content eventContent = Content.fromParts(Part.fromText(
                        msg.getContent() != null ? msg.getContent() : ""));

                Event event = Event.builder()
                        .author(author)
                        .content(eventContent)
                        .timestamp(System.currentTimeMillis())
                        .build();

                runner.sessionService().appendEvent(newSession, event).blockingGet();
                replayed++;
            } catch (Exception e) {
                log.warn("历史消息回放失败 role:{} content:{}", msg.getRole(),
                        msg.getContent() != null ? msg.getContent().substring(0, Math.min(50, msg.getContent().length())) : "", e);
            }
        }
        log.info("历史消息回放完成 {}/{} 条", replayed, replayMessages.size());

        // 4. 迁移 Redis 数据：旧 sessionId 的消息复制到新 sessionId
        migrateRedisData(userId, oldSessionId, newSessionId);

        // 5. 持久化新映射
        sessionContextRepository.saveSessionMapping(agentId, userId, newSessionId);

        // 6. 执行当前用户消息
        Flowable<Event> events = runner.runAsync(userId, newSessionId, currentContent);
        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return new SessionResult(outputs, newSessionId);
    }

    /** 将旧 sessionId 的 Redis 数据迁移到新 sessionId */
    private void migrateRedisData(String userId, String oldSessionId, String newSessionId) {
        List<ConversationMessageVO> allMessages = sessionContextRepository
                .getAllMessages(userId, oldSessionId);
        for (ConversationMessageVO msg : allMessages) {
            sessionContextRepository.appendMessage(userId, newSessionId, msg);
        }
        // 删除旧 session 数据
        sessionContextRepository.deleteSession(userId, oldSessionId);
        log.info("Redis 数据已迁移 oldSid:{} → newSid:{} messages:{}",
                oldSessionId, newSessionId, allMessages.size());
    }

}
