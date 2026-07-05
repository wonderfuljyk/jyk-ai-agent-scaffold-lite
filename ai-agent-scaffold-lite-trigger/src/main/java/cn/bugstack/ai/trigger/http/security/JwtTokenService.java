package cn.bugstack.ai.trigger.http.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT Token 服务
 * 签发和验证 JWT Token
 * @author jyk
 */
@Slf4j
@Component
public class JwtTokenService {

    @Value("${agent.security.jwt.secret:change-me-in-production-use-env-var}")
    private String secret;

    @Value("${agent.security.jwt.expiration-ms:3600000}")
    private long expirationMs;

    /**
     * 签发 Token
     *
     * @param userId 用户 ID
     * @return JWT Token 字符串
     */
    public String generateToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(SignatureAlgorithm.HS256, secret.getBytes())
                .compact();
    }

    /**
     * 验证并解析 Token
     *
     * @param token JWT Token
     * @return userId
     * @throws Exception 如果 Token 无效或过期
     */
    public String validateTokenAndGetUserId(String token) {
        return Jwts.parser()
                .setSigningKey(secret.getBytes())
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }
}
