package cn.bugstack.ai.domain.agent.service.armory.matter.experiment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * A/B 测试支持 —— 根据 userId hash 分配 Prompt 版本
 *
 * @author jyk
 */
@Slf4j
@Component
public class PromptABTest {

    /** 版本配置：version → 权重 */
    private static final Map<String, Integer> VERSIONS = Map.of("v1", 50, "v2", 50);

    /**
     * 根据 userId 选择 Prompt 版本
     */
    public String selectVersion(String userId, String agentId) {
        int hash = Math.abs(userId.hashCode() + agentId.hashCode());
        int bucket = hash % 100;
        int acc = 0;
        for (var e : VERSIONS.entrySet()) {
            acc += e.getValue();
            if (bucket < acc) return e.getKey();
        }
        return "v1";
    }

    /**
     * 加载指定版本的 Agent Prompt
     */
    public String getPrompt(String agentId, String version) {
        // 从配置或数据库加载不同版本的 Prompt
        // 当前返回固定文案，实际应查配置表
        String base = "你是智能助手，回答问题准确简洁。";
        if ("v2".equals(version)) {
            base = "你是智能助手，回答问题时先给出结论再展开解释。";
        }
        return base;
    }

    /** 获取所有版本 */
    public Map<String, Integer> getVersions() {
        return new HashMap<>(VERSIONS);
    }
}
