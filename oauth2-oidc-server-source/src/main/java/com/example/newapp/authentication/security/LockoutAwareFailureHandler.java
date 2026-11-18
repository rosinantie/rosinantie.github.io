package com.example.newapp.authentication.security;

import com.example.newapp.authentication.service.LoginAttemptService;
import com.example.newapp.authentication.service.LoginHistoryService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class LockoutAwareFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;
    private final LoginHistoryService loginHistoryService;

    public LockoutAwareFailureHandler(LoginAttemptService loginAttemptService, LoginHistoryService loginHistoryService) {
        super("/login?error");
        this.loginAttemptService = loginAttemptService;
        this.loginHistoryService = loginHistoryService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");
        log.warn("LOGIN FAILED username='{}' reason={}: {}", username, exception.getClass().getSimpleName(), exception.getMessage());
        if (username != null && !username.isBlank()) {
            loginHistoryService.record(username, request, false, exception.getClass().getSimpleName());
            loginAttemptService.onFailedLogin(username);
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
