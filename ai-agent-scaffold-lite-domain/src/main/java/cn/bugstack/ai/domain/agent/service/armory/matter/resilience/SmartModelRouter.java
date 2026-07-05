package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import cn.bugstack.ai.domain.agent.service.observability.AgentObservabilityService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 慢查询自动降级 —— 响应慢时自动切换更快模型
 *
 * @author jyk
 */
@Slf4j
@Component
public class SmartModelRouter {

    @Resource
    private AgentObservabilityService observabilityService;

    private static final Map<String, String> FALLBACK_MAP = Map.of(
            "gpt-4", "gpt-3.5-turbo",
            "gpt-4o", "gpt-4o-mini",
            "gpt-4.1", "gpt-4o-mini"
    );

    /**
     * 如果当前模型最近调用慢，建议切换到备用模型
     * @return 建议的模型名
     */
    public String selectModel(String preferredModel) {
        var metrics = observabilityService.getMetrics();
        Object avgDur = metrics.get("llmAvgDurationMs");
        if (avgDur instanceof Number dur && dur.longValue() > 8000) {
            String fallback = FALLBACK_MAP.getOrDefault(preferredModel, null);
            if (fallback != null) {
                log.warn("模型 {} 平均耗时 {}ms > 8s，切换为 {}", preferredModel, dur, fallback);
                return fallback;
            }
        }
        return preferredModel;
    }
}
