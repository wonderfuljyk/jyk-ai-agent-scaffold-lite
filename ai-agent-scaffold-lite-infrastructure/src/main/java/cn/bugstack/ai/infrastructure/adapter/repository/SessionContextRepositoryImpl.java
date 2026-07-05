package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.ISessionContextRepository;
import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.SessionRedisProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 会话上下文仓储 Redis 实现
 * <p>
 * 所有值以 JSON 字符串存取，手动序列化/反序列化，避免 Jackson 类型推断不可靠。
 *
 * @author jyk
 */
@Slf4j
@Repository
public class SessionContextRepositoryImpl implements ISessionContextRepository {

    @Autowired
    @Qualifier("stringRedisTemplate")
    private StringRedisTemplate redis;

    @Autowired
    private SessionRedisProperties props;

    @Autowired
    @Qualifier("redisObjectMapper")
    private ObjectMapper mapper;

    // ==================== 消息存取 ====================

    @Override
    public void appendMessage(String userId, String sessionId, ConversationMessageVO message) {
        if (isBlank(userId) || isBlank(sessionId) || message == null) {
            log.warn("appendMessage 跳过：参数为空");
            return;
        }
        String key = key(userId, sessionId);
        String json = toJson(message);
        if (json == null) return;

        try {
            redis.opsForList().rightPush(key, json);
            redis.opsForList().trim(key, -props.getMaxTurns(), -1);
            redis.expire(key, Duration.ofSeconds(props.getTtlSeconds()));
            log.info("会话消息已追加 key={} size={}", key, redis.opsForList().size(key));
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 appendMessage key={}", key, e);
        }
    }

    @Override
    public List<ConversationMessageVO> getRecentMessages(String userId, String sessionId, int count) {
        if (isBlank(userId) || isBlank(sessionId) || count <= 0) return Collections.emptyList();
        return readList(userId, sessionId, -count, -1);
    }

    @Override
    public List<ConversationMessageVO> getAllMessages(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) return Collections.emptyList();
        return readList(userId, sessionId, 0, -1);
    }

    private List<ConversationMessageVO> readList(String userId, String sessionId, long start, long end) {
        String key = key(userId, sessionId);
        try {
            List<String> raw = redis.opsForList().range(key, start, end);
            if (raw == null || raw.isEmpty()) {
                log.warn("Redis 读取为空 key={}", key);
                return Collections.emptyList();
            }
            List<ConversationMessageVO> result = new ArrayList<>();
            for (String json : raw) {
                ConversationMessageVO msg = fromJson(json);
                if (msg != null) result.add(msg);
            }
            return result;
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 readList key={}", key, e);
            return Collections.emptyList();
        }
    }

    @Override
    public long getMessageCount(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) return 0;
        try {
            Long size = redis.opsForList().size(key(userId, sessionId));
            return size != null ? size : 0;
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 getMessageCount", e);
            return 0;
        }
    }

    // ==================== 摘要（独立 Key） ====================

    @Override
    public Optional<ConversationMessageVO> getLatestSummary(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) return Optional.empty();
        try {
            String json = redis.opsForValue().get(summaryKey(userId, sessionId));
            ConversationMessageVO msg = fromJson(json);
            if (msg != null && "summary".equals(msg.getMessageType())) return Optional.of(msg);
            return Optional.empty();
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 getLatestSummary", e);
            return Optional.empty();
        }
    }

    @Override
    public void saveSummary(String userId, String sessionId, ConversationMessageVO summary) {
        if (isBlank(userId) || isBlank(sessionId) || summary == null) return;
        String json = toJson(summary);
        if (json == null) return;
        try {
            redis.opsForValue().set(summaryKey(userId, sessionId), json,
                    Duration.ofSeconds(props.getTtlSeconds()));
            log.info("会话摘要已保存 key={}", summaryKey(userId, sessionId));
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 saveSummary", e);
        }
    }

    // ==================== 会话 ID 映射 ====================

    @Override
    public Optional<String> getSessionId(String agentId, String userId) {
        if (isBlank(agentId) || isBlank(userId)) return Optional.empty();
        try {
            String sid = redis.opsForValue().get(mappingKey(agentId, userId));
            return Optional.ofNullable(sid);
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 getSessionId", e);
            return Optional.empty();
        }
    }

    @Override
    public void saveSessionMapping(String agentId, String userId, String sessionId) {
        if (isBlank(agentId) || isBlank(userId) || isBlank(sessionId)) return;
        try {
            redis.opsForValue().set(mappingKey(agentId, userId), sessionId,
                    Duration.ofSeconds(props.getTtlSeconds()));
            log.debug("会话映射已保存 key={}", mappingKey(agentId, userId));
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 saveSessionMapping", e);
        }
    }

    // ==================== 删除 / TTL ====================

    @Override
    public void deleteSession(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) return;
        try {
            redis.delete(List.of(key(userId, sessionId), summaryKey(userId, sessionId)));
            log.info("会话数据已删除");
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 deleteSession", e);
        }
    }

    @Override
    public void refreshTtl(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) return;
        long ttl = props.getTtlSeconds();
        try {
            redis.expire(key(userId, sessionId), Duration.ofSeconds(ttl));
            redis.expire(summaryKey(userId, sessionId), Duration.ofSeconds(ttl));
        } catch (DataAccessException e) {
            log.error("Redis 操作失败 refreshTtl", e);
        }
    }

    // ==================== 内部工具 ====================

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return null;
        }
    }

    private ConversationMessageVO fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, ConversationMessageVO.class);
        } catch (Exception e) {
            log.error("JSON 反序列化失败 json={}", json.substring(0, Math.min(100, json.length())), e);
            return null;
        }
    }

    private static String key(String userId, String sessionId) {
        return ISessionContextRepository.buildRedisKey(userId, sessionId);
    }

    private static String summaryKey(String userId, String sessionId) {
        return ISessionContextRepository.buildSummaryKey(userId, sessionId);
    }

    private static String mappingKey(String agentId, String userId) {
        return ISessionContextRepository.buildSessionMappingKey(agentId, userId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
