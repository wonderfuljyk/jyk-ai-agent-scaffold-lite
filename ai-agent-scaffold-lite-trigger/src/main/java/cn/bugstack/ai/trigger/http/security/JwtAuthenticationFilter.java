package cn.bugstack.ai.trigger.http.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器
 * 对 /api/v1/** 路径进行 Token 校验，排除公开接口
 * @author jyk
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private JwtTokenService jwtTokenService;

    public void setJwtTokenService(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /** 无需认证的公开路径前缀 */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/api/v1/query_ai_agent_config_list",
            "/api/v1/auth/",
            "/api/v1/debug/",
            "/api/v1/create_session"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // OPTIONS 预检请求放行（浏览器 CORS 跨域不带 Authorization 头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 校验 Authorization 头
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("缺少或无效的 Authorization 头 path:{} ip:{}", path, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"E0401\",\"info\":\"缺少或无效的 Authorization 头\"}");
            return;
        }

        try {
            String token = authHeader.substring(7);
            String userId = jwtTokenService.validateTokenAndGetUserId(token);
            request.setAttribute("userId", userId);
            chain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("Token 验证失败 path:{} ip:{} error:{}", path, request.getRemoteAddr(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"E0401\",\"info\":\"Token 无效或已过期\"}");
        }
    }
}
