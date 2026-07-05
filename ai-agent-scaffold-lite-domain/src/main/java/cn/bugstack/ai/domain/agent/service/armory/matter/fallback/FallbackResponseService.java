package cn.bugstack.ai.domain.agent.service.armory.matter.fallback;

import cn.bugstack.ai.domain.agent.model.valobj.properties.LlmResilienceProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 降级回复服务
 * 当 LLM 所有重试耗尽后，返回预设的兜底文案
 * @author jyk
 */
@Slf4j
@Service
public class FallbackResponseService {

    @Resource
    private LlmResilienceProperties properties;

    /**
     * 获取默认兜底回复
     */
    public String getDefaultReply() {
        return properties.getDefaultFallbackReply();
    }
}
