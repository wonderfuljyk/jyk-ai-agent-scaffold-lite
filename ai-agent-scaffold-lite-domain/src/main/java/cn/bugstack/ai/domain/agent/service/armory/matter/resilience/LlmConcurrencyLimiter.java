package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * LLM 调用并发控制
 * 基于信号量限制同时进行的 LLM 调用数，防止 API 过载
 *
 * @author jyk
 */
@Slf4j
@Component
public class LlmConcurrencyLimiter {

    /** 最大并发 LLM 调用数 */
    private static final int MAX_CONCURRENT_LLM_CALLS = 5;

    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_LLM_CALLS, true);

    /**
     * 获取 LLM 调用许可（阻塞直到可用）
     * @throws InterruptedException 如果等待被中断
     */
    public void acquire() throws InterruptedException {
        log.debug("LLM 并发许可等待中 (available={})", semaphore.availablePermits());
        semaphore.acquire();
        log.debug("LLM 并发许可已获取 (available={})", semaphore.availablePermits());
    }

    /**
     * 尝试获取许可，超时则返回 false
     * @param timeoutMs 超时毫秒
     */
    public boolean tryAcquire(long timeoutMs) {
        try {
            return semaphore.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放 LLM 调用许可
     */
    public void release() {
        semaphore.release();
        log.debug("LLM 并发许可已释放 (available={})", semaphore.availablePermits());
    }

    /** 当前可用许可数 */
    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
