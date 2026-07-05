package cn.bugstack.ai.domain.agent.service.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Token 用量追踪 + 成本估算
 *
 * @author jyk
 */
@Slf4j
@Component
public class TokenUsageTracker {

    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    private static final double COST_PER_1K_TOKENS = 0.00001;

    public void recordUsage(String model, int inputTokens, int outputTokens, String userId) {
        int total = inputTokens + outputTokens;
        if (total <= 0) return;

        counters.merge("input:" + model, (long) inputTokens, Long::sum);
        counters.merge("output:" + model, (long) outputTokens, Long::sum);
        counters.merge("total:user:" + userId, (long) total, Long::sum);

        double cost = total * COST_PER_1K_TOKENS;
        log.info("Token用量 model={} input={} output={} total={} userId={} estimatedCost=${}",
                model, inputTokens, outputTokens, total, userId, String.format("%.6f", cost));
    }

    public Map<String, Object> getUsageSummary() {
        Map<String, Object> summary = new ConcurrentHashMap<>();
        for (var e : counters.entrySet()) {
            summary.put(e.getKey(), e.getValue());
        }
        return summary;
    }
}
