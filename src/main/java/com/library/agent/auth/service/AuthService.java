package com.library.agent.auth.service;

import com.library.agent.auth.context.UserContext;
import com.library.agent.auth.dto.LoginRequest;
import com.library.agent.auth.dto.LoginResponse;
import com.library.agent.auth.dto.RegisterRequest;

public interface AuthService {

    LoginResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    void logout(String token);

    UserContext getCurrentUser();
}
