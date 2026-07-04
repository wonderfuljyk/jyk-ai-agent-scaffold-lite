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

@Slf4j
@Service
public class ChatService implements IChatService {

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
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 通过 ADK 创建会话
        Session session = runner.sessionService().createSession(appName, userId)
                .blockingGet();
        String sessionId = session.id();

        // 初始化 Redis 会话上下文（存储一条会话创建的系统消息）
        ConversationMessageVO initMsg = ConversationMessageVO.builder()
                .role("system")
                .content("Session created for agent: " + agentId)
                .messageType("query")
                .timestamp(LocalDateTime.now())
                .build();
        sessionContextRepository.appendMessage(userId, sessionId, initMsg);

        log.info("会话已创建并持久化 agentId:{} userId:{} sessionId:{}", agentId, userId, sessionId);
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        // 输入安全清洗（纵深防御）
        String sanitizedMessage = inputSanitizationService.sanitize(message);

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }
        String sessionId = createSession(agentId, userId);
        return handleMessage(agentId, userId, sessionId, sanitizedMessage);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 持久化用户消息到 Redis
        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("user")
                        .content(message)
                        .messageType("query")
                        .timestamp(LocalDateTime.now())
                        .build());

        // 执行 Agent
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        // 持久化 assistant 回复到 Redis
        String fullReply = String.join("\n", outputs);
        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("assistant")
                        .content(fullReply)
                        .messageType("reply")
                        .timestamp(LocalDateTime.now())
                        .build());

        // 异步检查是否需要生成摘要
        sessionSummaryService.checkAndSummarizeAsync(userId, sessionId);

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 持久化用户消息到 Redis
        sessionContextRepository.appendMessage(userId, sessionId,
                ConversationMessageVO.builder()
                        .role("user")
                        .content(message)
                        .messageType("query")
                        .timestamp(LocalDateTime.now())
                        .build());

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        Content userMsg = Content.fromParts(Part.fromText(message));

        RunConfig runConfig = RunConfig.builder()
                .setStreamingMode(RunConfig.StreamingMode.SSE)
                .build();

        // 包装 Flowable，在流结束后持久化 assistant 回复
        Flowable<Event> originalFlow = runner.runAsync(userId, sessionId, userMsg, runConfig);

        return originalFlow.doOnComplete(() -> {
            // 流式结束后无法直接获取完整文本，记录一个占位消息
            // 实际完整内容由上层 subscriber 负责收集和持久化
            log.debug("流式对话完成 userId:{} sessionId:{}", userId, sessionId);
            sessionSummaryService.checkAndSummarizeAsync(userId, sessionId);
        });
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        // 获取对象
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 构建参数
        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        for (ChatCommandEntity.Content.Text text : texts) {
            parts.add(Part.fromText(text.getMessage()));
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        for (ChatCommandEntity.Content.File file : files) {
            parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
            parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 持久化用户消息到 Redis
        String userMsgText = texts.stream()
                .map(ChatCommandEntity.Content.Text::getMessage)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        sessionContextRepository.appendMessage(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(),
                ConversationMessageVO.builder()
                        .role("user")
                        .content(userMsgText)
                        .messageType("query")
                        .timestamp(LocalDateTime.now())
                        .metadata(Map.of("file_count", files.size(), "inline_count", inlineDatas.size()))
                        .build());

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // 执行
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        // 持久化 assistant 回复到 Redis
        String fullReply = String.join("\n", outputs);
        sessionContextRepository.appendMessage(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(),
                ConversationMessageVO.builder()
                        .role("assistant")
                        .content(fullReply)
                        .messageType("reply")
                        .timestamp(LocalDateTime.now())
                        .build());

        // 异步检查是否需要生成摘要
        sessionSummaryService.checkAndSummarizeAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());

        return outputs;
    }

}
