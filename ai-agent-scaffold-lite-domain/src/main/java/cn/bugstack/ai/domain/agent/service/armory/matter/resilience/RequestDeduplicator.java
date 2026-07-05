package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 请求去重 + 幂等性
 * 60 秒内相同 userId+sessionId+messageHash 视为重复请求
 *
 * @author jyk
 */
@Slf4j
@Component
public class RequestDeduplicator {

    @Resource
    private StringRedisTemplate redis;

    private static final String PREFIX = "req:dedup:";
    private static final int WINDOW_SIZE = 10;
    private static final long WINDOW_SECONDS = 60;

    /**
     * 检查是否重复请求
     * @return true=重复，false=首次
     */
    public boolean isDuplicate(String userId, String sessionId, String message) {
        if (message == null) return false;
        String key = PREFIX + userId + ":" + sessionId;
        String hash = DigestUtils.md5DigestAsHex(message.getBytes(StandardCharsets.UTF_8));

        try {
            var recent = redis.opsForList().range(key, 0, WINDOW_SIZE - 1);
            if (recent != null && recent.stream().anyMatch(h -> h.equals(hash))) {
                log.warn("检测到重复请求 userId={} sessionId={}", userId, sessionId);
                return true;
            }
            redis.opsForList().leftPush(key, hash);
            redis.opsForList().trim(key, 0, WINDOW_SIZE - 1);
            redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            return false;
        } catch (Exception e) {
            log.error("请求去重检查失败", e);
            return false; // 故障时放行，不阻塞业务
        }
    }
}
