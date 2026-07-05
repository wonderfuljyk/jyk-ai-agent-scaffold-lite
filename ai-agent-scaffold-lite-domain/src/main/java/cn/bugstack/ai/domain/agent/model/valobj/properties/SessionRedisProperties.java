package cn.bugstack.ai.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话 Redis 配置属性
 * @author jyk
 */
@Data
@ConfigurationProperties(prefix = "agent.session.redis", ignoreInvalidFields = true)
public class SessionRedisProperties {

    /** 会话上下文的 TTL（秒），默认 2 小时 */
    private long ttlSeconds = 7200;

    /** 滑动窗口保留的最大消息条数（LTRIM -N -1） */
    private int maxTurns = 20;

    /** 触发摘要的队列长度阈值 */
    private int summaryThreshold = 10;

    /** 参与摘要生成的旧消息条数 */
    private int summaryWindowSize = 15;

    /** 摘要最大字数 */
    private int summaryMaxWords = 200;
}
