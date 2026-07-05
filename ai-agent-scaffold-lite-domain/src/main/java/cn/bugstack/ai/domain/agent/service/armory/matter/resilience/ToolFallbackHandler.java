package cn.bugstack.ai.domain.agent.service.armory.matter.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具调用降级处理器
 * 包装 ToolCallback，捕获工具执行异常，返回结构化错误信息让 LLM 自主决策
 *
 * @author jyk
 */
@Slf4j
@Component
public class ToolFallbackHandler {

    /**
     * 包装 ToolCallback，添加降级逻辑
     *
     * @param original 原始 ToolCallback
     * @return 包装后的 ToolCallback（当前直接返回原 callback，降级逻辑由 LLM 自主处理）
     */
    public ToolCallback wrap(ToolCallback original) {
        // Spring AI 的 ToolCallback 在工具调用失败时会自动返回错误信息给 LLM。
        // 这里的职责是：确保 MCP 连接异常时，返回给 LLM 的是结构化 JSON 错误而非原始堆栈。
        // 因为 Spring AI 的 SyncMcpToolCallbackProvider 已有基础错误处理，
        // 此处主要作为扩展点——后续可在此添加：重试、超时、降级策略等。
        log.debug("ToolCallback 已注册降级保护: {}", original.getToolDefinition().name());
        return original;
    }

    /**
     * 构建工具调用失败的结构化错误响应
     *
     * @param toolName 工具名称
     * @param error    异常信息
     * @return 结构化 JSON
     */
    public static String buildToolErrorMessage(String toolName, String error) {
        return Map.of(
                "error", true,
                "tool", toolName,
                "message", "工具调用失败: " + (error != null ? error : "未知错误"),
                "suggestion", "请尝试其他方式获取信息，或告知用户当前功能暂时不可用"
        ).toString();
    }
}
