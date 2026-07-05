package cn.bugstack.ai.domain.agent.service.security;

import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 输入安全清洗服务
 * <p>
 * 提供 XSS 防护、SQL 注入检测、敏感词拦截、输入长度截断等基础安全能力。
 *
 * @author jyk
 */
@Slf4j
@Service
public class InputSanitizationService {

    // XSS 模式：<script>, javascript:, onerror= 等
    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(<script.*?>.*?</script>)|(javascript:)|(on\\w+\\s*=)|(<iframe)|(<object)|(<embed)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // SQL 注入模式
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "\\b(SELECT|INSERT|UPDATE|DELETE|DROP|UNION|ALTER|TRUNCATE|EXEC|EXECUTE)\\b",
            Pattern.CASE_INSENSITIVE);

    // 敏感词黑名单（可从配置加载）
    private final Set<String> blockedKeywords = new HashSet<>();

    @PostConstruct
    public void init() {
        // 默认敏感词
        blockedKeywords.addAll(Arrays.asList(
                "rm -rf", "DROP TABLE", "<script>", "<?php", "eval(",
                "system(", "exec(", "cmd.exe", "powershell"
        ));
    }

    /**
     * 清洗用户输入
     *
     * @param input 原始输入
     * @return 清洗后的输入
     * @throws AppException 如果包含禁止内容
     */
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String sanitized = input.trim();

        // 长度截断
        if (sanitized.length() > 10000) {
            log.warn("输入长度超限，已截断 originalLength:{}", sanitized.length());
            sanitized = sanitized.substring(0, 10000);
        }

        // XSS 过滤
        if (XSS_PATTERN.matcher(sanitized).find()) {
            log.warn("检测到 XSS 攻击模式，已过滤");
            sanitized = XSS_PATTERN.matcher(sanitized).replaceAll("[filtered]");
        }

        // SQL 注入检测（仅告警，不修改内容以避免误伤正常文本）
        if (SQL_INJECTION_PATTERN.matcher(sanitized).find()) {
            log.warn("检测到可疑 SQL 模式，内容已标记");
            sanitized = SQL_INJECTION_PATTERN.matcher(sanitized).replaceAll("[sql_filtered]");
        }

        // 敏感词拦截
        for (String keyword : blockedKeywords) {
            if (sanitized.toLowerCase().contains(keyword.toLowerCase())) {
                log.warn("检测到敏感词: {}", keyword);
                throw new AppException(ResponseCode.E0003.getCode(),
                        ResponseCode.E0003.getInfo());
            }
        }

        return sanitized;
    }
}
