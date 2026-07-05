package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话命令，实体对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/17 16:52
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatCommandEntity {

    private String agentId;

    private String userId;

    private String sessionId;

    private List<Content.Text> texts;

    private List<Content.File> files;

    private List<Content.InlineData> inlineDatas;

    @Data
    public static class Content {

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            private String message;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            private String fileUri;
            private String mimeType;
            private String detectedType;   // image/audio/video/document/other

            /** 根据扩展名自动推断文件类型 */
            public void autoDetectType() {
                if (fileUri == null) { this.detectedType = "other"; return; }
                String lower = fileUri.toLowerCase();
                if (lower.matches(".*\\.(png|jpg|jpeg|gif|bmp|webp|svg)$")) this.detectedType = "image";
                else if (lower.matches(".*\\.(wav|mp3|ogg|flac|aac|m4a)$")) this.detectedType = "audio";
                else if (lower.matches(".*\\.(mp4|avi|mov|mkv|webm)$")) this.detectedType = "video";
                else if (lower.matches(".*\\.(pdf|doc|docx|txt|md|csv|json|xml|yaml|yml)$")) this.detectedType = "document";
                else this.detectedType = "other";
            }
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            private byte[] bytes;
            private String mimeType;

            /** 根据 MIME 类型推断 */
            public String detectedType() {
                if (mimeType == null) return "other";
                if (mimeType.startsWith("image/")) return "image";
                if (mimeType.startsWith("audio/")) return "audio";
                if (mimeType.startsWith("video/")) return "video";
                return "other";
            }
        }

    }

    public ChatCommandEntity buildSessionCommand(String agentId, String userId) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);
        return chatCommandEntity;
    }

    public ChatCommandEntity buildChatCommand(String agentId, String userId, String message) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);

        List<Content.Text> texts = new ArrayList<>();
        texts.add(new Content.Text(message));

        chatCommandEntity.setTexts(texts);

        return chatCommandEntity;
    }

}
