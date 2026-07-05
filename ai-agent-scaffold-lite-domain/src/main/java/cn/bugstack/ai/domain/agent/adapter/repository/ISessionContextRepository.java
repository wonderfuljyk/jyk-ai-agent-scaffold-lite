package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;

import java.util.List;
import java.util.Optional;

/**
 * 会话上下文仓储接口（DDD 端口）
 * 使用 Redis List 存储对话历史，精确到 userId + sessionId
 * @author jyk
 */
public interface ISessionContextRepository {

    /**
     * 追加消息到会话队列尾部
     * 执行 RPUSH → LTRIM → EXPIRE，每个命令自身是原子的
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param message   消息对象
     */
    void appendMessage(String userId, String sessionId, ConversationMessageVO message);

    /**
     * 获取最近的 N 条消息（LRANGE）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param count     获取条数
     * @return 消息列表（按时间顺序）
     */
    List<ConversationMessageVO> getRecentMessages(String userId, String sessionId, int count);

    /**
     * 获取会话全部消息
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<ConversationMessageVO> getAllMessages(String userId, String sessionId);

    /**
     * 获取最新的摘要消息（如果存在）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 摘要消息
     */
    Optional<ConversationMessageVO> getLatestSummary(String userId, String sessionId);

    /**
     * 保存摘要到队列头部（LPUSH）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param summary   摘要消息
     */
    void saveSummary(String userId, String sessionId, ConversationMessageVO summary);

    /**
     * 获取会话消息总数（LLEN）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 消息总数
     */
    long getMessageCount(String userId, String sessionId);

    /**
     * 删除会话数据
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    void deleteSession(String userId, String sessionId);

    /**
     * 刷新会话 TTL
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    void refreshTtl(String userId, String sessionId);

    /**
     * 构建 Redis Key（对话历史）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return Redis Key: agent:context:{userId}:{sessionId}
     */
    static String buildRedisKey(String userId, String sessionId) {
        return "agent:context:" + userId + ":" + sessionId;
    }

    /**
     * 构建摘要 Redis Key（独立存储，不受 LTRIM 影响）
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return Redis Key: agent:summary:{userId}:{sessionId}
     */
    static String buildSummaryKey(String userId, String sessionId) {
        return "agent:summary:" + userId + ":" + sessionId;
    }

    // ==================== 会话 ID 映射 ====================

    /**
     * 获取 agentId+userId 对应的 sessionId
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @return sessionId，如果不存在返回 empty
     */
    Optional<String> getSessionId(String agentId, String userId);

    /**
     * 保存 agentId+userId → sessionId 映射
     *
     * @param agentId   智能体 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    void saveSessionMapping(String agentId, String userId, String sessionId);

    /**
     * 构建会话映射 Redis Key
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @return Redis Key: agent:session:{agentId}:{userId}
     */
    static String buildSessionMappingKey(String agentId, String userId) {
        return "agent:session:" + agentId + ":" + userId;
    }
}
