package cn.bugstack.ai.config;

import cn.bugstack.ai.trigger.http.security.JwtAuthenticationFilter;
import cn.bugstack.ai.trigger.http.security.JwtTokenService;
import jakarta.annotation.Resource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全过滤器配置
 * 注册 JWT 认证过滤器
 *
 * @author jyk
 */
@Configuration
public class SecurityFilterConfig {

    @Resource
    private JwtTokenService jwtTokenService;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        filter.setJwtTokenService(jwtTokenService);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(jwtAuthenticationFilter);
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(1);
        return registration;
    }
}
