package com.example.newapp.authentication.security;

import com.example.newapp.authentication.service.LoginAttemptService;
import com.example.newapp.authentication.service.LoginHistoryService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Resets lockout state and records login history on a successful password check.
 * No MFA step yet - once that feature exists, this is where a redirect to
 * /mfa/verify (parking the Authentication in the session instead of letting it
 * through) would get added, the same way the original project does it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final LoginAttemptService loginAttemptService;
    private final LoginHistoryService loginHistoryService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws ServletException, IOException {
        log.info("LOGIN OK username='{}'", authentication.getName());
        loginAttemptService.onSuccessfulLogin(authentication.getName());

        if (authentication.getPrincipal() instanceof AppUserPrincipal p) {
            String eventType = "GOOGLE".equals(p.getAppUser().getAuthProvider()) ? "GOOGLE" : "PASSWORD";
            loginHistoryService.record(authentication.getName(), request, true, eventType);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
