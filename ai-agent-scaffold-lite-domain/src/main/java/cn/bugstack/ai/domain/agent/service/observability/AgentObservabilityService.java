package cn.bugstack.ai.domain.agent.service.observability;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 可观测性服务
 * 记录 LLM 调用耗时、工具调用次数、成功率等指标
 *
 * @author jyk
 */
@Slf4j
@Service
public class AgentObservabilityService {

    /** 工具调用计数：toolName → count */
    private final Map<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();

    /** 工具调用失败计数 */
    private final Map<String, AtomicLong> toolFailCounts = new ConcurrentHashMap<>();

    /** LLM 调用耗时记录（最近 100 次） */
    private final long[] llmDurations = new long[100];
    private int durationIdx = 0;

    /** 总 LLM 调用次数 */
    private final AtomicLong llmCallCount = new AtomicLong(0);

    /** LLM 失败次数 */
    private final AtomicLong llmFailCount = new AtomicLong(0);

    // ===== LLM 调用追踪 =====

    public void recordLlmCall(long durationMs, boolean success) {
        llmCallCount.incrementAndGet();
        if (!success) llmFailCount.incrementAndGet();
        synchronized (llmDurations) {
            llmDurations[durationIdx++ % llmDurations.length] = durationMs;
        }
        log.info("OBSERVABILITY | LLM调用 | 耗时={}ms | 成功={}", durationMs, success);
    }

    // ===== 工具调用追踪 =====

    public void recordToolCall(String toolName, long durationMs, boolean success) {
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        if (!success) toolFailCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        log.info("OBSERVABILITY | 工具调用 | tool={} | 耗时={}ms | 成功={}", toolName, durationMs, success);
    }

    // ===== Agent 执行追踪 =====

    public void recordAgentExecution(String agentName, long durationMs, boolean success) {
        log.info("OBSERVABILITY | Agent执行 | agent={} | 耗时={}ms | 成功={}", agentName, durationMs, success);
    }

    // ===== 指标查询 =====

    public Map<String, Object> getMetrics() {
        double avgDuration = 0;
        int count = 0;
        synchronized (llmDurations) {
            for (long d : llmDurations) {
                if (d > 0) { avgDuration += d; count++; }
            }
        }
        if (count > 0) avgDuration /= count;

        return Map.of(
                "llmTotalCalls", llmCallCount.get(),
                "llmFailures", llmFailCount.get(),
                "llmAvgDurationMs", Math.round(avgDuration),
                "llmSuccessRate", llmCallCount.get() > 0
                        ? String.format("%.1f%%", 100.0 * (llmCallCount.get() - llmFailCount.get()) / llmCallCount.get())
                        : "N/A",
                "toolCallCounts", Map.copyOf(toolCallCounts),
                "toolFailCounts", Map.copyOf(toolFailCounts)
        );
    }

    /** 返回一个计时器，用于测量耗时 */
    public Timer startTimer() {
        return new Timer();
    }

    public static class Timer {
        private final Instant start = Instant.now();
        public long elapsedMs() {
            return Duration.between(start, Instant.now()).toMillis();
        }
    }
}
