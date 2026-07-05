package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

/**
 * LLM 调用重试耗尽异常
 * 当所有重试次数用尽后抛出，触发降级逻辑
 * @author jyk
 */
public class LlmExhaustedException extends RuntimeException {

    public LlmExhaustedException(String message) {
        super(message);
    }

    public LlmExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
