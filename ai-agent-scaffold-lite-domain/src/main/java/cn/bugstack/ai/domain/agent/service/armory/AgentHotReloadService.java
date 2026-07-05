package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 热更新服务
 * 支持运行时注册/注销 Agent，无需重启应用
 *
 * @author jyk
 */
@Slf4j
@Service
public class AgentHotReloadService {

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @Resource
    private IArmoryService armoryService;

    /**
     * 重新加载所有 Agent 配置
     */
    public Map<String, Object> reloadAll(List<AiAgentConfigTableVO> configs) {
        try {
            armoryService.acceptArmoryAgents(configs);
            log.info("Agent 配置热更新完成，共 {} 个配置", configs.size());
            return Map.of("success", true, "count", configs.size());
        } catch (Exception e) {
            log.error("Agent 热更新失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 注销指定 Agent
     */
    public Map<String, Object> unregisterAgent(String agentId) {
        try {
            // 从 Spring 容器移除
            if (applicationContext.containsBean(agentId)) {
                var factory = (org.springframework.beans.factory.support.DefaultListableBeanFactory)
                        applicationContext.getAutowireCapableBeanFactory();
                factory.destroySingleton(agentId);
                log.info("Agent 已注销 agentId={}", agentId);
                return Map.of("success", true, "agentId", agentId, "action", "unregistered");
            }
            return Map.of("success", false, "agentId", agentId, "reason", "Agent 不存在");
        } catch (Exception e) {
            log.error("Agent 注销失败 agentId={}", agentId, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 列出当前已注册的 Agent
     */
    public List<String> listRegisteredAgents() {
        return java.util.Arrays.stream(applicationContext.getBeanDefinitionNames())
                .filter(name -> {
                    try {
                        return applicationContext.getBean(name) instanceof AiAgentRegisterVO;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }
}
