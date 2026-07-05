package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ADK Session 状态持久化
 * 将 Agent 间传递的 {output-key} 值序列化到 Redis Hash
 *
 * @author jyk
 */
@Slf4j
@Component
public class AdkSessionPersistence {

    @Resource
    private StringRedisTemplate redis;

    private static final String PREFIX = "adk:session:";
    private static final long TTL_SECONDS = 7200;

    /**
     * 保存 ADK Session 状态到 Redis
     */
    public void saveState(String sessionId, Map<String, Object> state) {
        if (sessionId == null || state == null || state.isEmpty()) return;
        String key = PREFIX + sessionId;
        Map<String, String> flat = new HashMap<>();
        for (var e : state.entrySet()) {
            if (e.getValue() instanceof String s) flat.put(e.getKey(), s);
        }
        if (flat.isEmpty()) return;
        try {
            redis.opsForHash().putAll(key, flat);
            redis.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("ADK Session 状态已持久化 key={} fields={}", key, flat.size());
        } catch (Exception e) {
            log.error("ADK Session 状态持久化失败 key={}", key, e);
        }
    }

    /**
     * 从 Redis 恢复 ADK Session 状态
     */
    public Map<String, Object> restoreState(String sessionId) {
        if (sessionId == null) return Map.of();
        String key = PREFIX + sessionId;
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (raw.isEmpty()) return Map.of();
            Map<String, Object> result = new HashMap<>();
            for (var e : raw.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            log.info("ADK Session 状态已恢复 key={} fields={}", key, result.size());
            return result;
        } catch (Exception e) {
            log.error("ADK Session 状态恢复失败 key={}", key, e);
            return Map.of();
        }
    }
}
