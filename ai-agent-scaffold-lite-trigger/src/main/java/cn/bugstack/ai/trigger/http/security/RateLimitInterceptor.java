package cn.bugstack.ai.trigger.http.security;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 限流拦截器
 * 基于 Guava RateLimiter 实现每用户令牌桶限流
 * @author jyk
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @jakarta.annotation.Resource
    private DynamicRateLimiter dynamicRateLimiter;

    /** 尝试获取令牌的超时时间 */
    private static final long ACQUIRE_TIMEOUT_MS = 1000;

    private final ConcurrentHashMap<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {

        // 从请求属性获取 userId（由 JWT Filter 设置），否则用 IP
        String userId = (String) request.getAttribute("userId");
        if (userId == null) {
            userId = request.getRemoteAddr();
        }

        RateLimiter limiter = userLimiters.computeIfAbsent(userId,
                k -> RateLimiter.create(dynamicRateLimiter.getCurrentRateLimit()));

        if (!limiter.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log.warn("请求限流触发 userId:{} ip:{} path:{}",
                    userId, request.getRemoteAddr(), request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"E0429\",\"info\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        return true;
    }
}
