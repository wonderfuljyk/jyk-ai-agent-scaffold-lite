package cn.bugstack.ai.infrastructure.mcp.csdn;

import lombok.Data;

@Data
public class ArticleResponseDTO {
    private Integer code;
    private String traceId;
    private ArticleData data;
    private String msg;
    private Boolean successful;  // CSDN 实际返回字段
    @Data
    public static class ArticleData {
        private String url;
        private Long id;
        private String qrcode;
        private String title;
        private String description;
    }
}
