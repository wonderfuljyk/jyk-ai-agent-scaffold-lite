package cn.bugstack.ai.infrastructure.mcp.csdn;

import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn.ArticleFunctionRequest;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn.ArticleFunctionResponse;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn.CSDNApiProperties;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn.ICSDNPort;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;

@Slf4j
@Component
public class CSDNPort implements ICSDNPort {

    @Resource
    private CSDNApiProperties csdnApiProperties;

    private final ICSDNService csdnService;

    public CSDNPort() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://blog-console-api.csdn.net")
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        csdnService = retrofit.create(ICSDNService.class);
    }

    @Override
    public ArticleFunctionResponse writeArticle(ArticleFunctionRequest request) throws IOException {
        // 构建请求DTO
        ArticleRequestDTO dto = new ArticleRequestDTO();
        dto.setTitle(request.getTitle());
        dto.setMarkdowncontent(request.getMarkdowncontent());
        dto.setContent(request.getContent());
        dto.setTags(request.getTags());
        dto.setDescription(request.getDescription());
        dto.setCategories(csdnApiProperties.getCategories());

        // 打印完整请求日志（脱敏处理）
        log.info("CSDN发帖请求 - 标题:{} 标签:{} 分类:{} 内容长度:{}",
                request.getTitle(),
                request.getTags(),
                csdnApiProperties.getCategories(),
                request.getContent() != null ? request.getContent().length() : 0);

        // 执行请求
        Call<ArticleResponseDTO> call = csdnService.saveArticle(dto, csdnApiProperties.getCookie());
        Response<ArticleResponseDTO> response = call.execute();

        // 打印完整响应信息
        log.info("CSDN发帖响应 - HTTP状态码:{} 是否成功:{}",
                response.code(),
                response.isSuccessful());

        // 检查HTTP响应状态
        if (!response.isSuccessful()) {
            log.error("CSDN API HTTP错误 - 状态码:{} 错误信息:{}",
                    response.code(),
                    response.message());

            ArticleFunctionResponse result = new ArticleFunctionResponse();
            result.setCode(response.code());
            result.setMsg("HTTP请求失败: " + response.message());
            return result;
        }

        // 获取响应体
        ArticleResponseDTO body = response.body();
        log.info("CSDN发帖响应体: {}", JSON.toJSONString(body));

        // 检查响应体是否为空
        if (body == null) {
            log.error("CSDN API 响应体为空");
            ArticleFunctionResponse result = new ArticleFunctionResponse();
            result.setCode(500);
            result.setMsg("CSDN API 响应体为空");
            return result;
        }

        // 构建返回结果
        ArticleFunctionResponse result = new ArticleFunctionResponse();
        result.setCode(body.getCode());
        result.setMsg(body.getMsg());

        // 检查 CSDN 业务返回状态
        if (body.getSuccessful() != null && !body.getSuccessful()) {
            // 优先使用CSDN返回的错误码和信息，而不是覆盖
            Integer errorCode = body.getCode() != null ? body.getCode() : -1;
            String errorMsg = body.getMsg() != null ? body.getMsg() : "未知错误";

            log.warn("CSDN发布业务失败 - 错误码:{} 错误信息:{}", errorCode, errorMsg);

            result.setCode(errorCode);
            result.setMsg("CSDN发布失败: " + errorMsg);

            // 根据错误信息给出更具体的提示
            if (errorMsg != null && errorMsg.contains("登录")) {
                result.setMsg("CSDN发布失败: 登录状态已过期，请重新登录");
            } else if (errorMsg != null && errorMsg.contains("标题")) {
                result.setMsg("CSDN发布失败: 文章标题可能已存在或包含敏感词");
            } else if (errorMsg != null && (errorMsg.contains("频率") || errorMsg.contains("频繁"))) {
                result.setMsg("CSDN发布失败: 发布频率过快，请稍后重试");
            }

            return result;
        }

        // 发布成功
        log.info("CSDN发帖成功 - 标题:{}", request.getTitle());
        result.setCode(0);
        result.setMsg("发布成功");


        return result;
    }
}