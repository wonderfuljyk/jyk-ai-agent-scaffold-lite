package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 请求去重 + 幂等缓存
 * 60 秒内重复请求直接返回上次缓存的回复，不调用 LLM
 *
 * @author jyk
 */
@Slf4j
@Component
public class RequestDeduplicator {

    @Resource
    private StringRedisTemplate redis;

    private static final String DEDUP_PREFIX = "req:dedup:";
    private static final String CACHE_PREFIX = "req:cache:";
    private static final int WINDOW_SIZE = 10;
    private static final long WINDOW_SECONDS = 60;

    /**
     * 检查是否重复请求
     * @return 消息 hash（用于缓存查找），为 null 表示首次
     */
    public String checkDuplicate(String userId, String sessionId, String message) {
        if (message == null) return null;
        String dedupKey = DEDUP_PREFIX + userId + ":" + sessionId;
        String hash = DigestUtils.md5DigestAsHex(message.getBytes(StandardCharsets.UTF_8));

        try {
            var recent = redis.opsForList().range(dedupKey, 0, WINDOW_SIZE - 1);
            if (recent != null && recent.stream().anyMatch(h -> h.equals(hash))) {
                log.warn("检测到重复请求 userId={} sessionId={}", userId, sessionId);
                return hash;
            }
            redis.opsForList().leftPush(dedupKey, hash);
            redis.opsForList().trim(dedupKey, 0, WINDOW_SIZE - 1);
            redis.expire(dedupKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            log.error("请求去重检查失败", e);
            return null;
        }
    }

    /** 缓存 LLM 回复结果 */
    public void cacheReply(String userId, String sessionId, String hash, String reply) {
        if (hash == null || reply == null) return;
        try {
            String cacheKey = CACHE_PREFIX + userId + ":" + sessionId + ":" + hash;
            redis.opsForValue().set(cacheKey, reply, WINDOW_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("缓存回复失败", e);
        }
    }

    /** 获取缓存的回复 */
    public String getCachedReply(String userId, String sessionId, String hash) {
        if (hash == null) return null;
        try {
            return redis.opsForValue().get(CACHE_PREFIX + userId + ":" + sessionId + ":" + hash);
        } catch (Exception e) {
            log.error("读取缓存回复失败", e);
            return null;
        }
    }
}
