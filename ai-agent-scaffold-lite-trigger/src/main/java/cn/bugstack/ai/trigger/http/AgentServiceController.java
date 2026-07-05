package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.security.InputSanitizationService;
import cn.bugstack.ai.domain.agent.service.security.OutputGuardrailsService;
import cn.bugstack.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private InputSanitizationService inputSanitizationService;

    @Resource
    private OutputGuardrailsService outputGuardrailsService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        log.info("查询智能体配置列表");
        List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

        List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
            AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
            responseDTO.setAgentId(agentConfig.getAgentId());
            responseDTO.setAgentName(agentConfig.getAgentName());
            responseDTO.setAgentDesc(agentConfig.getAgentDesc());
            return responseDTO;
        }).collect(Collectors.toList());

        return Response.<List<AiAgentConfigResponseDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTOS)
                .build();
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(
            @Valid @RequestBody CreateSessionRequestDTO requestDTO) {
        log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
        String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

        CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
        responseDTO.setSessionId(sessionId);

        return Response.<CreateSessionResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(
            @RequestParam("agentId") String agentId,
            @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO requestDTO) {
        log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());

        // 输入安全清洗
        String sanitizedMessage = inputSanitizationService.sanitize(requestDTO.getMessage());

        String sessionId = requestDTO.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
        }

        List<String> messages = chatService.handleMessage(
                requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, sanitizedMessage);

        // 输出安全护栏
        String fullContent = String.join("\n", messages);
        String filteredContent = outputGuardrailsService.filter(fullContent);

        ChatResponseDTO responseDTO = new ChatResponseDTO();
        responseDTO.setContent(filteredContent);
        responseDTO.setSessionId(sessionId);

        return Response.<ChatResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@Valid @RequestBody ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);

        // 输入安全清洗
        String sanitizedMessage = inputSanitizationService.sanitize(requestDTO.getMessage());

        log.info("流式对话 agentId:{} userId:{} sessionId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId());

        chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(),
                        requestDTO.getSessionId(), sanitizedMessage)
                .subscribe(
                        event -> {
                            try {
                                // 对每个流式事件进行输出护栏过滤
                                String content = event.stringifyContent();
                                String filtered = outputGuardrailsService.filter(content);
                                emitter.send(filtered);
                            } catch (Exception e) {
                                log.error("流式对话发送失败", e);
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }

}
