package com.library.agent.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.auth.config.AuthTokenProperties;
import com.library.agent.auth.context.UserContext;
import com.library.agent.auth.context.UserContextHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AuthTokenFilter implements Filter {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthTokenProperties authTokenProperties;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (isWhiteList(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String token = resolveToken(httpRequest);
        if (token == null || token.isBlank()) {
            writeUnauthorized(httpResponse, "未登录");
            return;
        }

        String redisKey = authTokenProperties.getRedisKeyPrefix() + token;
        String userContextJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (userContextJson == null || userContextJson.isBlank()) {
            writeUnauthorized(httpResponse, "登录已过期");
            return;
        }

        UserContext userContext;
        try {
            userContext = objectMapper.readValue(userContextJson, UserContext.class);
        } catch (Exception e) {
            writeUnauthorized(httpResponse, "登录状态无效");
            return;
        }

        try {
            UserContextHolder.set(userContext);
            refreshTokenTtl(redisKey);
            chain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private boolean isWhiteList(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return path.equals("/auth/register")
                || path.equals("/auth/login")
                || path.equals("/error")
                || path.startsWith("/static/")
                || path.equals("/test.html");
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return request.getHeader("X-Auth-Token");
    }

    private void refreshTokenTtl(String redisKey) {
        stringRedisTemplate.expire(redisKey, authTokenProperties.getExpireSeconds(), TimeUnit.SECONDS);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Map.of(
                "code", 401,
                "message", message
        ));
        response.getWriter().write(body);
    }
}
