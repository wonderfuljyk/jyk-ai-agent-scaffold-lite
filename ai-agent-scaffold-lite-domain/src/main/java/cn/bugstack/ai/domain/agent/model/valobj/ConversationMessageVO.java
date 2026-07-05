package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对话消息值对象
 * 序列化为 JSON 存入 Redis List
 * @author jyk
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationMessageVO {

    /** 角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 消息类型：query / reply / tool_message / summary */
    private String messageType;

    /** 消息时间戳 */
    private LocalDateTime timestamp;

    /** 扩展元数据：token 数量、模型名称等 */
    private Map<String, Object> metadata;
}
