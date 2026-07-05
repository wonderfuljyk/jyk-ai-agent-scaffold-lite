package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话消息查询响应
 *
 * @author jyk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessagesResponseDTO {

    private String userId;
    private String sessionId;
    private long messageCount;
    private List<MessageItem> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageItem {
        private String role;
        private String content;
        private String messageType;
        private String timestamp;
    }
}
