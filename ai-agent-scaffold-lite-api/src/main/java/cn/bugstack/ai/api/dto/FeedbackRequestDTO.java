package cn.bugstack.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户反馈请求
 *
 * @author jyk
 */
@Data
public class FeedbackRequestDTO {

    @NotBlank(message = "agentId 不能为空")
    private String agentId;

    @NotBlank(message = "userId 不能为空")
    private String userId;

    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    /** 用户原始提问 */
    private String query;

    /** AI 回复内容 */
    private String reply;

    /** "like" 或 "dislike" */
    @NotBlank(message = "rating 不能为空")
    private String rating;

    /** 可选备注 */
    private String comment;
}
