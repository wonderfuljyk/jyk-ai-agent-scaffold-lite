package cn.bugstack.ai.domain.agent.service.armory.discovery;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力自描述 —— 列出所有已注册 Agent 的能力信息
 *
 * @author jyk
 */
@Slf4j
@Component
public class AgentDiscovery {

    @Resource
    private AiAgentAutoConfigProperties configProperties;

    public List<AgentCapability> listCapabilities() {
        List<AgentCapability> result = new ArrayList<>();
        var tables = configProperties.getTables();
        if (tables == null) return result;

        for (var entry : tables.entrySet()) {
            var vo = entry.getValue();
            var agent = vo.getAgent();
            var module = vo.getModule();
            var agents = module != null ? module.getAgents() : null;
            var workflows = module != null ? module.getAgentWorkflows() : null;

            List<String> subAgentNames = agents != null
                    ? agents.stream().map(AiAgentConfigTableVO.Module.Agent::getName).toList()
                    : List.of();
            List<String> workflowTypes = workflows != null
                    ? workflows.stream().map(w -> w.getType() + ":" + w.getName()).toList()
                    : List.of();

            result.add(new AgentCapability(
                    agent.getAgentId(),
                    agent.getAgentName(),
                    agent.getAgentDesc(),
                    subAgentNames,
                    workflowTypes,
                    Map.of("mcp", hasMcp(module), "skills", hasSkills(module))
            ));
        }
        return result;
    }

    private boolean hasMcp(AiAgentConfigTableVO.Module module) {
        if (module == null || module.getChatModel() == null) return false;
        var mcpList = module.getChatModel().getToolMcpList();
        return mcpList != null && !mcpList.isEmpty();
    }

    private boolean hasSkills(AiAgentConfigTableVO.Module module) {
        if (module == null || module.getChatModel() == null) return false;
        var skills = module.getChatModel().getToolSkillsList();
        return skills != null && !skills.isEmpty();
    }

    public record AgentCapability(String agentId, String name, String description,
            List<String> subAgents, List<String> workflows, Map<String, Boolean> features) {}
}
