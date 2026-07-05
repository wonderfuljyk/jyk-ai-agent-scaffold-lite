package cn.bugstack.ai.domain.agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 输出护栏服务
 * <p>
 * 对 LLM 输出进行安全过滤，包括 PII 脱敏（手机号、身份证号、邮箱）。
 *
 * @author jyk
 */
@Slf4j
@Service
public class OutputGuardrailsService {

    // 中国大陆手机号
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");

    // 中国大陆身份证号
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");

    // 邮箱地址
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    /**
     * 过滤输出内容
     *
     * @param output 原始 LLM 输出
     * @return 过滤后的输出
     */
    public String filter(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        String filtered = output;

        // 手机号脱敏
        if (PHONE_PATTERN.matcher(filtered).find()) {
            int count = (int) PHONE_PATTERN.matcher(filtered).results().count();
            filtered = PHONE_PATTERN.matcher(filtered).replaceAll("[PHONE_REDACTED]");
            log.info("输出中脱敏 {} 个手机号", count);
        }

        // 身份证号脱敏
        if (ID_CARD_PATTERN.matcher(filtered).find()) {
            int count = (int) ID_CARD_PATTERN.matcher(filtered).results().count();
            filtered = ID_CARD_PATTERN.matcher(filtered).replaceAll("[ID_REDACTED]");
            log.info("输出中脱敏 {} 个身份证号", count);
        }

        // 邮箱部分脱敏：user@example.com → user@***.com
        if (EMAIL_PATTERN.matcher(filtered).find()) {
            filtered = EMAIL_PATTERN.matcher(filtered).replaceAll(match -> {
                String email = match.group();
                int atIndex = email.indexOf('@');
                int lastDot = email.lastIndexOf('.');
                if (atIndex > 0 && lastDot > atIndex) {
                    return email.substring(0, atIndex) + "@***" + email.substring(lastDot);
                }
                return "[EMAIL_REDACTED]";
            });
        }

        return filtered;
    }
}
