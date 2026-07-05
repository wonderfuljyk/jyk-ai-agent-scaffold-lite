package cn.bugstack.ai.domain.agent.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Query 路由服务
 * 基于关键词 + 规则引擎分类用户意图，自动选择最合适的 Agent
 *
 * @author jyk
 */
@Slf4j
@Service
public class QueryRouterService {

    // 代码相关关键词
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(写|编写|生成|实现|开发|代码|程序|函数|类|方法|算法|排序|debug|修|bug|优化|重构|单元测试)",
            Pattern.CASE_INSENSITIVE);

    // 研究分析关键词
    private static final Pattern RESEARCH_PATTERN = Pattern.compile(
            "(研究|分析|对比|调研|报告|总结|归纳|评估|综述|展望|趋势|前景|技术路线)",
            Pattern.CASE_INSENSITIVE);

    // 闲聊关键词
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(你好|hi|hello|hey|嗨|在吗|谢谢|再见|拜拜|晚安|早上好|下午好|晚上好)",
            Pattern.CASE_INSENSITIVE);

    /**
 * 支持 Skills 关键词
 */
private static final Pattern SKILL_BATTLE_PLAN = Pattern.compile(
        "(性能|优化|清理|磁盘|内存|CPU|系统信息|进程|磁盘空间)",
        Pattern.CASE_INSENSITIVE);
private static final Pattern SKILL_PDF = Pattern.compile(
        "(PDF|表单|填写|提取|转换|识别|文档处理)",
        Pattern.CASE_INSENSITIVE);

/** 默认 agentId（通用对话，支持 skills） */
private static final String DEFAULT_AGENT_ID = "100003";

/**
 * 根据用户消息路由到最合适的 Agent + Skill
 *
 * @param message 用户消息
 * @return RouteResult 包含 agentId 和建议的 skill
 */
public RouteResult route(String message) {
    if (message == null || message.isBlank()) {
        return new RouteResult(DEFAULT_AGENT_ID, null);
    }

    String trimmed = message.trim();

    // 1. 闲聊 → 默认 Agent
    if (CHAT_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) {
        log.info("Query 路由: 闲聊 → agentId={}", DEFAULT_AGENT_ID);
        return new RouteResult(DEFAULT_AGENT_ID, null);
    }

    // 2. 代码任务
    if (CODE_PATTERN.matcher(trimmed).find()) {
        log.info("Query 路由: 代码任务 → agentId=100001");
        return new RouteResult("100001", null);
    }

    // 3. 研究分析
    if (RESEARCH_PATTERN.matcher(trimmed).find()) {
        log.info("Query 路由: 研究分析 → agentId=100002");
        return new RouteResult("100002", null);
    }

    // 4. Skills 匹配（自动注入相关 skill 提示）
    String skill = null;
    if (SKILL_BATTLE_PLAN.matcher(trimmed).find()) {
        skill = "battle-plan";
        log.info("Query 路由: 匹配 Skill battle-plan");
    } else if (SKILL_PDF.matcher(trimmed).find()) {
        skill = "pdf";
        log.info("Query 路由: 匹配 Skill pdf");
    }

    // 5. 默认
    log.info("Query 路由: 默认 → agentId={} skill={}", DEFAULT_AGENT_ID, skill);
    return new RouteResult(DEFAULT_AGENT_ID, skill);
}

/** 兼容旧接口 */
public String routeToAgentId(String message) {
    return route(message).agentId();
}

/** 路由结果 */
public record RouteResult(String agentId, String skill) {}
}
