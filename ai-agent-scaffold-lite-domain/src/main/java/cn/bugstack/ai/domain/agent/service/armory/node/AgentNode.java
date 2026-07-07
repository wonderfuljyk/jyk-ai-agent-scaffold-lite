package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.LlmResilienceProperties;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.fallback.FallbackResponseService;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmConcurrencyLimiter;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmRetryHandler;
import cn.bugstack.ai.domain.agent.service.armory.matter.contract.SchemaValidator;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.SmartModelRouter;
import cn.bugstack.ai.domain.agent.service.observability.AgentObservabilityService;
import cn.bugstack.ai.domain.agent.service.observability.AgentTraceContext;
import cn.bugstack.ai.domain.agent.service.observability.TokenUsageTracker;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import java.util.concurrent.ThreadPoolExecutor;
import com.google.adk.agents.LlmAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private LlmRetryHandler llmRetryHandler;

    @Resource
    private FallbackResponseService fallbackResponseService;

    @Resource
    private LlmResilienceProperties llmResilienceProperties;

    @Resource
    private LlmConcurrencyLimiter llmConcurrencyLimiter;

    @Resource
    private AgentObservabilityService agentObservabilityService;

    @Resource
    private TokenUsageTracker tokenUsageTracker;

    @Resource
    private SmartModelRouter smartModelRouter;

    @Resource
    private AgentTraceContext agentTraceContext;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private SchemaValidator schemaValidator;

    @Resource
    private cn.bugstack.ai.domain.agent.service.armory.matter.resilience.WorkflowCheckpoint workflowCheckpoint;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            MySpringAI mySpringAI = new MySpringAI(chatModel);
            mySpringAI.setLlmRetryHandler(llmRetryHandler);
            mySpringAI.setFallbackResponseService(fallbackResponseService);
            mySpringAI.setResilienceProperties(llmResilienceProperties);
            mySpringAI.setConcurrencyLimiter(llmConcurrencyLimiter);
            mySpringAI.setObservabilityService(agentObservabilityService);
            mySpringAI.setTokenUsageTracker(tokenUsageTracker);
            mySpringAI.setAgentTraceContext(agentTraceContext);
            mySpringAI.setAgentName(agentConfig.getName());
            mySpringAI.setThreadPoolExecutor(threadPoolExecutor);
            mySpringAI.setSchemaValidator(schemaValidator);
            mySpringAI.setOutputSchema(agentConfig.getOutputSchema());
            mySpringAI.setWorkflowCheckpoint(workflowCheckpoint);
            mySpringAI.setAgentId(aiAgentConfigTableVO.getAgent().getAgentId());

            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(mySpringAI)
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
