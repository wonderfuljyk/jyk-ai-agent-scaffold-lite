package cn.bugstack.ai.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 调用容错配置属性
 * @author jyk
 */
@Data
@ConfigurationProperties(prefix = "agent.llm.resilience", ignoreInvalidFields = true)
public class LlmResilienceProperties {

    /** 是否启用容错机制 */
    private boolean enabled = true;

    /** 最大重试次数 */
    private int maxRetries = 3;

    /** 初始退避时间（毫秒） */
    private long initialBackoffMs = 1000;

    /** 退避倍数 */
    private double backoffMultiplier = 2.0;

    /** 最大退避时间（毫秒） */
    private long maxBackoffMs = 10000;

    /** 熔断器阈值：连续失败多少次后熔断 */
    private int circuitBreakerThreshold = 5;

    /** 熔断器超时时间（毫秒）：熔断后多久进入半开状态 */
    private long circuitBreakerTimeoutMs = 60000;

    /** 降级兜底回复文案 */
    private String defaultFallbackReply = "抱歉，AI 服务暂时不可用，请稍后重试。";

    /** LLM 调用超时（秒） */
    private long llmTimeoutSeconds = 300;

    /** 工具调用超时（秒） */
    private long toolTimeoutSeconds = 100;

    /** 工作流整体超时（秒） */
    private long workflowTimeoutSeconds = 120;
}
