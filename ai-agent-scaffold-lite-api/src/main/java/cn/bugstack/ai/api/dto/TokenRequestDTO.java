package cn.bugstack.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token 签发请求
 *
 * @author jyk
 */
@Data
public class TokenRequestDTO {

    @NotBlank(message = "userId 不能为空")
    private String userId;
}
