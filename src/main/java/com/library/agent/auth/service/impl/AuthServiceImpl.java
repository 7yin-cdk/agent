package com.library.agent.auth.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.auth.config.AuthTokenProperties;
import com.library.agent.auth.context.UserContext;
import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.auth.dto.LoginRequest;
import com.library.agent.auth.dto.LoginResponse;
import com.library.agent.auth.dto.RegisterRequest;
import com.library.agent.auth.dto.UserInfoResponse;
import com.library.agent.auth.service.AuthService;
import com.library.agent.auth.util.TokenUtil;
import com.library.agent.entity.AgentUser;
import com.library.agent.mapper.AgentUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    private final AgentUserMapper agentUserMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthTokenProperties authTokenProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        validateRegisterRequest(request);

        AgentUser existingUser = agentUserMapper.selectByUsername(request.getUsername().trim());
        if (existingUser != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        AgentUser user = new AgentUser();
        user.setUsername(request.getUsername().trim());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(normalizeNickname(request));
        user.setStatus(USER_STATUS_ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        agentUserMapper.insert(user);
        return createLoginResponse(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        validateLoginRequest(request);

        AgentUser user = agentUserMapper.selectByUsername(request.getUsername().trim());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用");
        }

        return createLoginResponse(user);
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(buildRedisKey(token));
        UserContextHolder.clear();
    }

    @Override
    public UserContext getCurrentUser() {
        UserContext userContext = UserContextHolder.get();
        if (userContext == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userContext;
    }

    private LoginResponse createLoginResponse(AgentUser user) {
        String token = TokenUtil.generateToken();
        UserContext userContext = buildUserContext(user);
        saveUserContext(token, userContext);

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setExpireSeconds(authTokenProperties.getExpireSeconds());
        response.setUser(toUserInfoResponse(userContext));
        return response;
    }

    private UserContext buildUserContext(AgentUser user) {
        UserContext userContext = new UserContext();
        userContext.setUserId(user.getId());
        userContext.setUsername(user.getUsername());
        userContext.setNickname(user.getNickname());
        userContext.setLoginTime(LocalDateTime.now());
        return userContext;
    }

    private void saveUserContext(String token, UserContext userContext) {
        try {
            String json = objectMapper.writeValueAsString(userContext);
            stringRedisTemplate.opsForValue().set(
                    buildRedisKey(token),
                    json,
                    authTokenProperties.getExpireSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存登录状态失败", e);
        }
    }

    private UserInfoResponse toUserInfoResponse(UserContext userContext) {
        UserInfoResponse response = new UserInfoResponse();
        response.setUserId(userContext.getUserId());
        response.setUsername(userContext.getUsername());
        response.setNickname(userContext.getNickname());
        return response;
    }

    private String buildRedisKey(String token) {
        return authTokenProperties.getRedisKeyPrefix() + token;
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注册参数不能为空");
        }
        validateUsernameAndPassword(request.getUsername(), request.getPassword());
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "登录参数不能为空");
        }
        validateUsernameAndPassword(request.getUsername(), request.getPassword());
    }

    private void validateUsernameAndPassword(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
    }

    private String normalizeNickname(RegisterRequest request) {
        if (request.getNickname() == null || request.getNickname().trim().isEmpty()) {
            return request.getUsername().trim();
        }
        return request.getNickname().trim();
    }
}
