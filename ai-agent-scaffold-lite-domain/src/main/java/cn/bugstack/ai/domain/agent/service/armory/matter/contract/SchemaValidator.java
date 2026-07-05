package cn.bugstack.ai.domain.agent.service.armory.matter.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 输入/输出 JSON Schema 校验器
 * 运行时校验 Agent 之间的数据契约
 *
 * @author jyk
 */
@Slf4j
@Component
public class SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 校验数据是否符合 JSON Schema
     *
     * @param schemaJson Schema 定义（JSON 字符串）
     * @param data       待校验数据
     * @return 校验结果
     */
    public ValidationResult validate(String schemaJson, String data) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return ValidationResult.pass(); // 无 Schema 则跳过
        }

        List<String> errors = new ArrayList<>();

        try {
            JsonNode schema = mapper.readTree(schemaJson);
            JsonNode dataNode = data != null ? mapper.readTree(data) : mapper.nullNode();

            // 类型校验
            if (schema.has("type")) {
                String expectedType = schema.get("type").asText();
                if (!matchesType(dataNode, expectedType)) {
                    errors.add("期望类型: " + expectedType + ", 实际: " + dataNode.getNodeType());
                }
            }

            // 必填字段校验
            if (schema.has("required") && dataNode.isObject()) {
                for (JsonNode req : schema.get("required")) {
                    String field = req.asText();
                    if (!dataNode.has(field) || dataNode.get(field).isNull()) {
                        errors.add("缺少必填字段: " + field);
                    }
                }
            }

            // 属性类型校验
            if (schema.has("properties") && dataNode.isObject()) {
                JsonNode props = schema.get("properties");
                var it = props.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    String key = entry.getKey();
                    JsonNode propSchema = entry.getValue();
                    if (dataNode.has(key) && propSchema.has("type")) {
                        String expected = propSchema.get("type").asText();
                        if (!matchesType(dataNode.get(key), expected)) {
                            errors.add("字段 " + key + " 期望类型: " + expected);
                        }
                    }
                }
            }

            if (errors.isEmpty()) {
                return ValidationResult.pass();
            }
            log.warn("Schema 校验失败: {}", errors);
            return ValidationResult.fail(errors);
        } catch (Exception e) {
            log.error("Schema 校验异常", e);
            return ValidationResult.fail(List.of("Schema 解析失败: " + e.getMessage()));
        }
    }

    private boolean matchesType(JsonNode node, String type) {
        return switch (type) {
            case "string" -> node.isTextual();
            case "number", "integer" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    /** 校验结果 */
    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult pass() { return new ValidationResult(true, List.of()); }
        public static ValidationResult fail(List<String> errors) { return new ValidationResult(false, errors); }
    }
}
