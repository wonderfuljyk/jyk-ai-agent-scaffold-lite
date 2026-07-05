package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import cn.bugstack.ai.domain.agent.model.valobj.properties.LlmResilienceProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * LLM 调用重试处理器
 * <p>
 * 实现指数退避重试，区分可重试异常和不可重试异常：
 * - 可重试：网络超时、连接异常、HTTP 429、HTTP 5xx
 * - 不可重试：认证失败(401)、参数错误(400)
 *
 * @author jyk
 */
@Slf4j
@Component
public class LlmRetryHandler {

    @Resource
    private LlmResilienceProperties properties;

    /**
     * 带重试的执行
     *
     * @param call      实际调用
     * @param modelName 模型名称（用于日志）
     * @param <T>       返回值类型
     * @return 调用结果
     * @throws LlmExhaustedException 重试耗尽
     */
    public <T> T executeWithRetry(Supplier<T> call, String modelName) {
        if (!properties.isEnabled()) {
            return call.get();
        }

        int attempt = 0;
        long backoff = properties.getInitialBackoffMs();
        Exception lastException = null;

        while (attempt < properties.getMaxRetries()) {
            try {
                attempt++;
                return call.get();
            } catch (Exception e) {
                lastException = e;

                if (!isRetryable(e)) {
                    log.warn("LLM 调用失败(不可重试) model:{} attempt:{}/{} error:{}",
                            modelName, attempt, properties.getMaxRetries(), e.getMessage());
                    throw new LlmExhaustedException(
                            "LLM call failed with non-retryable error: " + e.getMessage(), e);
                }

                if (attempt >= properties.getMaxRetries()) {
                    log.error("LLM 调用失败(重试耗尽) model:{} attempt:{}/{} error:{}",
                            modelName, attempt, properties.getMaxRetries(), e.getMessage());
                    throw new LlmExhaustedException(
                            "LLM call exhausted after " + attempt + " attempts: " + e.getMessage(), e);
                }

                log.warn("LLM 调用重试中 model:{} attempt:{}/{} backoff:{}ms error:{}",
                        modelName, attempt, properties.getMaxRetries(), backoff, e.getMessage());

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmExhaustedException("Retry interrupted", ie);
                }

                backoff = Math.min(
                        (long) (backoff * properties.getBackoffMultiplier()),
                        properties.getMaxBackoffMs());
            }
        }

        throw new LlmExhaustedException(
                "LLM call failed after " + attempt + " attempts", lastException);
    }

    /**
     * 判断异常是否可重试
     */
    private boolean isRetryable(Exception e) {
        // 网络超时
        if (e instanceof SocketTimeoutException || e instanceof TimeoutException) {
            return true;
        }
        // 连接异常
        if (e instanceof ConnectException || e instanceof IOException) {
            return true;
        }

        // 检查异常链中是否包含可重试类型
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }

        // 通过异常消息判断
        String message = e.getMessage();
        if (message != null) {
            // HTTP 429 Too Many Requests (限流)
            if (message.contains("429")) {
                return true;
            }
            // HTTP 5xx 服务端错误
            if (message.contains("500") || message.contains("502")
                    || message.contains("503") || message.contains("504")) {
                return true;
            }
            // HTTP 401, 403 不可重试（认证/授权问题）
            if (message.contains("401") || message.contains("403")) {
                return false;
            }
            // HTTP 400 不可重试（参数错误）
            if (message.contains("400")) {
                return false;
            }
        }

        // 含 "timeout" 关键词的异常默认可重试
        if (message != null && message.toLowerCase().contains("timeout")) {
            return true;
        }

        // 无法判断时默认不重试，直接失败
        return false;
    }
}
