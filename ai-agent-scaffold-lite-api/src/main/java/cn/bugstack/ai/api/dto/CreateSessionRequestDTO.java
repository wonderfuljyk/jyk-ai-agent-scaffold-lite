package cn.bugstack.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    @NotBlank(message = "agentId 不能为空")
    @Size(max = 64, message = "agentId 长度不能超过64个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "agentId 包含非法字符")
    private String agentId;

    @NotBlank(message = "userId 不能为空")
    @Size(max = 64, message = "userId 长度不能超过64个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "userId 包含非法字符")
    private String userId;

}
