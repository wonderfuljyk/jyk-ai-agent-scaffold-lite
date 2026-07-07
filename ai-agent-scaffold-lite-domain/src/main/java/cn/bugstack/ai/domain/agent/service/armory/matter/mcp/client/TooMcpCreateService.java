package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.client;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;


public interface TooMcpCreateService {
    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;
}
