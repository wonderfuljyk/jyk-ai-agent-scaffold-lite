package cn.bugstack.ai.trigger.http.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 动态限流 —— 根据时间段调整 QPS
 *
 * @author jyk
 */
@Slf4j
@Component
public class DynamicRateLimiter {

    /**
     * 根据当前时间返回限流 QPS
     */
    public double getCurrentRateLimit() {
        LocalTime now = LocalTime.now();

        // 工作日高峰 9:00-11:00 → 收紧
        if (now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(11, 0))) {
            return 5.0;
        }
        // 晚间/凌晨 → 放宽
        if (now.isAfter(LocalTime.of(18, 0)) || now.isBefore(LocalTime.of(8, 0))) {
            return 20.0;
        }
        return 10.0;
    }
}
