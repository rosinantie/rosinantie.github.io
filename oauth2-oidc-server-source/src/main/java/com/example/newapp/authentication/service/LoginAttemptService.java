package com.example.newapp.authentication.service;

import com.example.newapp.authentication.config.AppSecurityProperties;
import com.example.newapp.authentication.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Failed-attempt tracking + temporary account lockout. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final AppUserRepository appUserRepository;
    private final AppSecurityProperties properties;

    public void onSuccessfulLogin(String username) {
        appUserRepository.findByUsername(username).ifPresentOrElse(user -> {
            if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
                log.info("RESET lockout state for username='{}' (was {} failed attempts)", username, user.getFailedLoginAttempts());
            }
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            appUserRepository.save(user);
        }, () -> log.warn("onSuccessfulLogin called for unknown username='{}'", username));
    }

    public void onFailedLogin(String username) {
        appUserRepository.findByUsername(username).ifPresentOrElse(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= properties.security().maxFailedLogins()) {
                Instant lockedUntil = Instant.now().plus(properties.security().lockoutDurationMinutes(), ChronoUnit.MINUTES);
                user.setLockedUntil(lockedUntil);
                log.warn("ACCOUNT LOCKED username='{}' attempts={} lockedUntil={}", username, attempts, lockedUntil);
            } else {
                log.warn("Failed login #{}/{} for username='{}'", attempts, properties.security().maxFailedLogins(), username);
            }
            appUserRepository.save(user);
        }, () -> log.warn("Failed login attempt for unknown username='{}'", username));
    }
}
