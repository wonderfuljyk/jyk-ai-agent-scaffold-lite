package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 工作流检查点
 * 每次 Agent 执行完成后保存 {output-key → output-value} 到 Redis Hash
 * 宕机恢复时可跳过已完成节点，从断点继续
 *
 * @author jyk
 */
@Slf4j
@Component
public class WorkflowCheckpoint {

    @Resource
    private StringRedisTemplate redis;

    private static final String PREFIX = "ckpt:";
    private static final long TTL_HOURS = 2;

    /**
     * 保存检查点：Agent 输出快照
     */
    public void save(String sessionId, String agentName, String outputText) {
        if (sessionId == null || agentName == null || outputText == null) return;
        try {
            String key = PREFIX + sessionId;
            redis.opsForHash().put(key, agentName, outputText);
            redis.expire(key, TTL_HOURS, TimeUnit.HOURS);
            log.debug("检查点已保存 sessionId={} agent={}", sessionId, agentName);
        } catch (Exception e) {
            log.error("检查点保存失败 agent={}", agentName, e);
        }
    }

    /**
     * 加载指定 Session 的全部检查点
     * @return Map<agentName, output>
     */
    public Map<String, String> loadAll(String sessionId) {
        if (sessionId == null) return Map.of();
        try {
            String key = PREFIX + sessionId;
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            Map<String, String> result = new HashMap<>();
            for (var e : raw.entrySet()) {
                result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            log.error("检查点加载失败 sessionId={}", sessionId, e);
            return Map.of();
        }
    }

    /**
     * 检查是否已完成指定 Agent（用于宕机恢复时跳过）
     */
    public boolean isCompleted(String sessionId, String agentName) {
        if (sessionId == null || agentName == null) return false;
        try {
            String key = PREFIX + sessionId;
            return Boolean.TRUE.equals(redis.opsForHash().hasKey(key, agentName));
        } catch (Exception e) {
            return false;
        }
    }

    /** 清理检查点 */
    public void clear(String sessionId) {
        if (sessionId == null) return;
        try {
            redis.delete(PREFIX + sessionId);
        } catch (Exception e) {
            log.error("检查点清理失败 sessionId={}", sessionId, e);
        }
    }
}
