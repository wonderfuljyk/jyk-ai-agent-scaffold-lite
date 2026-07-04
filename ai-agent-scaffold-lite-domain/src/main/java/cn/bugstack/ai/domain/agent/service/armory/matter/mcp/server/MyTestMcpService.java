package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class MyTestMcpService {

    @Tool(description = "小写字母转换为大写字母")
    public XxxResponse toUpperCase(XxxRequest request) {
        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setContent(request.getWord().toUpperCase());
        return xxxResponse;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("英文单词，字符串，字母。例如: good,xiaofuge")
        private String word;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("单词转换结果")
        private String content;
    }

    @Tool(description = "执行两个数字的精确乘法计算")
    public CalcResponse multiply(CalcRequest request) {
        log.info("【本地工具被调用】计算: {} * {}", request.getNumA(), request.getNumB());
        CalcResponse response = new CalcResponse();
        // 使用 BigDecimal 保证精度，碾压大模型的幻觉
        BigDecimal result = request.getNumA().multiply(request.getNumB());
        response.setResult(result.toPlainString());
        return response;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CalcRequest {
        @JsonProperty(required = true, value = "numA")
        @JsonPropertyDescription("乘数A，数字类型")
        private BigDecimal numA;
        @JsonProperty(required = true, value = "numB")
        @JsonPropertyDescription("乘数B，数字类型")
        private BigDecimal numB;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CalcResponse {
        @JsonProperty(required = true, value = "result")
        @JsonPropertyDescription("乘法计算的精确结果")
        private String result;
    }

}
