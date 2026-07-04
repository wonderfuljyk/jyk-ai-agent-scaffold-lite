package cn.bugstack.ai.trigger.http.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志切面
 * 记录所有 API 调用的审计信息：userId、IP、method、path、status、duration
 * @author jyk
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    @Around("execution(* cn.bugstack.ai.trigger.http.AgentServiceController.*(..))")
    public Object logApiCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String method = "UNKNOWN";
        String path = "UNKNOWN";
        String userId = "anonymous";
        String remoteAddr = "UNKNOWN";

        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            method = request.getMethod();
            path = request.getRequestURI();
            remoteAddr = request.getRemoteAddr();
            String uid = (String) request.getAttribute("userId");
            if (uid != null) {
                userId = uid;
            }
        }

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            log.info("AUDIT | user={} | ip={} | {} {} | status=SUCCESS | duration={}ms",
                    userId, remoteAddr, method, path, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("AUDIT | user={} | ip={} | {} {} | status=FAILED | duration={}ms | error={}",
                    userId, remoteAddr, method, path, duration, e.getMessage());
            throw e;
        }
    }
}
