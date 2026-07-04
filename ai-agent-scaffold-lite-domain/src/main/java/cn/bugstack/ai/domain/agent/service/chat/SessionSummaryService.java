package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.adapter.repository.ISessionContextRepository;
import cn.bugstack.ai.domain.agent.model.valobj.ConversationMessageVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.SessionRedisProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 会话摘要服务
 * <p>
 * 当会话消息数超过阈值时，异步使用小模型将旧消息浓缩为摘要，
 * LPUSH 到 Redis 队列头部，实现"1条摘要 + 最近N条记录"的记忆窗口。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Service
public class SessionSummaryService {

    @Resource
    private ISessionContextRepository sessionContextRepository;

    @Resource
    private SessionRedisProperties sessionRedisProperties;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    /**
     * 异步检查并生成摘要
     * 每次 RPUSH 后调用，如果 LLEN > threshold 则触发异步摘要生成
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     */
    public void checkAndSummarizeAsync(String userId, String sessionId) {
        long count = sessionContextRepository.getMessageCount(userId, sessionId);
        int threshold = sessionRedisProperties.getSummaryThreshold();

        if (count > threshold) {
            threadPoolExecutor.execute(() -> {
                try {
                    generateAndStoreSummary(userId, sessionId);
                } catch (Exception e) {
                    log.error("异步摘要生成失败 userId:{} sessionId:{}", userId, sessionId, e);
                }
            });
        }
    }

    /**
     * 生成摘要并存到 Redis 队列头部
     */
    private void generateAndStoreSummary(String userId, String sessionId) {
        int windowSize = sessionRedisProperties.getSummaryWindowSize();
        int maxWords = sessionRedisProperties.getSummaryMaxWords();

        // 获取旧消息（前 windowSize 条，排除最近 5 条）
        List<ConversationMessageVO> oldMessages = sessionContextRepository
                .getRecentMessages(userId, sessionId, windowSize);

        if (oldMessages.isEmpty()) {
            return;
        }

        // 过滤出非摘要消息
        List<ConversationMessageVO> conversationMessages = oldMessages.stream()
                .filter(msg -> !"summary".equals(msg.getMessageType()))
                .collect(Collectors.toList());

        if (conversationMessages.isEmpty()) {
            return;
        }

        // 构建摘要文本
        String conversationText = conversationMessages.stream()
                .map(msg -> String.format("[%s]: %s", msg.getRole(), msg.getContent()))
                .collect(Collectors.joining("\n"));

        // 生成简单摘要（基于规则拼接，后续可替换为调用小模型）
        String summary = buildSummary(conversationText, maxWords);

        // LPUSH 摘要到队列头部
        ConversationMessageVO summaryMsg = ConversationMessageVO.builder()
                .role("system")
                .content(summary)
                .messageType("summary")
                .timestamp(LocalDateTime.now())
                .metadata(Map.of("summarized_count", conversationMessages.size()))
                .build();

        sessionContextRepository.saveSummary(userId, sessionId, summaryMsg);
        log.info("会话摘要已生成并存储 userId:{} sessionId:{} summarizedCount:{} summaryWords:{}",
                userId, sessionId, conversationMessages.size(), summary.length());
    }

    /**
     * 构建摘要文本
     * <p>
     * 当前实现：提取对话关键信息拼接。
     * 高阶玩法：可替换为调用小参数模型（如 GLM-Flash / DeepSeek-Lite）生成高质量摘要。
     *
     * @param conversationText 对话文本
     * @param maxWords         最大字数
     * @return 摘要文本
     */
    private String buildSummary(String conversationText, int maxWords) {
        // 简单实现：截取前 maxWords 个字符 + 关键信息提取
        if (conversationText.length() <= maxWords) {
            return "[对话摘要] " + conversationText;
        }

        // 提取每段的首句作为摘要
        String[] lines = conversationText.split("\n");
        StringBuilder summary = new StringBuilder("[对话摘要] 此前对话要点：\n");
        int wordCount = 0;

        for (String line : lines) {
            // 提取冒号后的内容首句
            int colonIndex = line.indexOf(": ");
            String content = colonIndex > 0 ? line.substring(colonIndex + 2) : line;

            // 截取前 50 个字符作为要点
            String point = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            summary.append("- ").append(point).append("\n");
            wordCount += point.length();

            if (wordCount >= maxWords) {
                break;
            }
        }

        return summary.toString().trim();
    }
}
