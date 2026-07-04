package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.ISessionContextRepository;
import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.SessionRedisProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


@Slf4j
@Repository
public class SessionContextRepositoryImpl implements ISessionContextRepository {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private SessionRedisProperties sessionRedisProperties;

    @Override
    public void appendMessage(String userId, String sessionId, ConversationMessageVO message) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        int maxTurns = sessionRedisProperties.getMaxTurns();
        long ttlSeconds = sessionRedisProperties.getTtlSeconds();

        // RPUSH：追加到 List 尾部
        redisTemplate.opsForList().rightPush(key, message);

        // LTRIM：仅保留最近 maxTurns 条消息，实现滑动窗口裁剪
        redisTemplate.opsForList().trim(key, -maxTurns, -1);

        // EXPIRE：刷新 TTL，活跃用户永不过期
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));

        log.debug("会话消息已追加 key={}, maxTurns={}, ttl={}s", key, maxTurns, ttlSeconds);
    }

    @Override
    public List<ConversationMessageVO> getRecentMessages(String userId, String sessionId, int count) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        List<Object> rawList = redisTemplate.opsForList().range(key, -count, -1);
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        return rawList.stream()
                .filter(obj -> obj instanceof ConversationMessageVO)
                .map(obj -> (ConversationMessageVO) obj)
                .toList();
    }

    @Override
    public List<ConversationMessageVO> getAllMessages(String userId, String sessionId) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        List<Object> rawList = redisTemplate.opsForList().range(key, 0, -1);
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        return rawList.stream()
                .filter(obj -> obj instanceof ConversationMessageVO)
                .map(obj -> (ConversationMessageVO) obj)
                .toList();
    }

    @Override
    public Optional<ConversationMessageVO> getLatestSummary(String userId, String sessionId) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        Object first = redisTemplate.opsForList().index(key, 0);
        if (first instanceof ConversationMessageVO msg && "summary".equals(msg.getMessageType())) {
            return Optional.of(msg);
        }
        return Optional.empty();
    }

    @Override
    public void saveSummary(String userId, String sessionId, ConversationMessageVO summary) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        // LPUSH：摘要插入队列头部
        redisTemplate.opsForList().leftPush(key, summary);
        // 刷新 TTL
        redisTemplate.expire(key, Duration.ofSeconds(sessionRedisProperties.getTtlSeconds()));
        log.info("会话摘要已保存 key={}", key);
    }

    @Override
    public long getMessageCount(String userId, String sessionId) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    @Override
    public void deleteSession(String userId, String sessionId) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        redisTemplate.delete(key);
        log.info("会话数据已删除 key={}", key);
    }

    @Override
    public void refreshTtl(String userId, String sessionId) {
        String key = ISessionContextRepository.buildRedisKey(userId, sessionId);
        redisTemplate.expire(key, Duration.ofSeconds(sessionRedisProperties.getTtlSeconds()));
    }
}
