package com.example.newapp.authentication.service;

import com.example.newapp.authentication.entity.LoginHistory;
import com.example.newapp.authentication.repository.LoginHistoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Records every login attempt (success or failure) so a user can audit "was this
 * really me?" and so an operator can spot brute-force / credential-stuffing patterns.
 * Deliberately a plain append log, independent of AppUser, rather than fields on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginHistoryService {

    private static final int MAX_USER_AGENT_LENGTH = 500;

    private final LoginHistoryRepository loginHistoryRepository;

    public void record(String username, HttpServletRequest request, boolean success, String eventType) {
        LoginHistory entry = LoginHistory.builder()
                .username(username)
                .success(success)
                .eventType(eventType)
                .ipAddress(clientIp(request))
                .userAgent(trimUserAgent(request.getHeader("User-Agent")))
                .build();
        loginHistoryRepository.save(entry);
        log.info("LOGIN HISTORY username='{}' success={} eventType={} ip={}",
                username, success, eventType, entry.getIpAddress());
    }

    public List<LoginHistory> recentFor(String username) {
        return loginHistoryRepository.findTop50ByUsernameOrderByOccurredAtDesc(username);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String trimUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        return userAgent.length() > MAX_USER_AGENT_LENGTH ? userAgent.substring(0, MAX_USER_AGENT_LENGTH) : userAgent;
    }
}
