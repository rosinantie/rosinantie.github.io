package com.example.newapp.authentication.security;

import com.example.newapp.authentication.entity.AppUser;
import com.example.newapp.authentication.entity.Role;
import com.example.newapp.authentication.repository.AppUserRepository;
import com.example.newapp.authentication.repository.RoleRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Runs after Google verifies the user's identity. Google only proves "who";
 * it still has to become one of our own AppUser records so the rest of the
 * server (JWT claims, lockout, token issuance) treats every login path
 * identically. We then hand off to LoginSuccessHandler so a Google login
 * resets lockout state and records login history exactly like a password
 * login does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginSuccessHandler loginSuccessHandler;
    private final OAuth2AuthorizedClientService authorizedClientService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        logGoogleIssuedTokens(authentication, oauth2User);

        if (email == null || email.isBlank()) {
            log.warn("Google login did not return an email attribute; cannot link an account");
            response.sendRedirect(request.getContextPath() + "/login?error");
            return;
        }

        AppUser user = appUserRepository.findByEmail(email).orElseGet(() -> createGoogleUser(email, oauth2User));
        log.info("GOOGLE LOGIN username='{}' email='{}' (provider={})", user.getUsername(), email, user.getAuthProvider());

        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication localAuth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        // Reuse the exact same lockout-reset + login-history + saved-request redirect logic as password login.
        loginSuccessHandler.onAuthenticationSuccess(request, response, localAuth);
    }

    /**
     * STUDY MODE: logs the actual tokens Google just issued - the OIDC id_token is right there on
     * the principal, and the OAuth2 access_token Spring used to fetch it is stashed separately in
     * OAuth2AuthorizedClientService (not on the Authentication itself). Fine for localhost study
     * purposes, never in a real deployment - these are live bearer credentials for the user's
     * Google account.
     */
    private void logGoogleIssuedTokens(Authentication authentication, OAuth2User oauth2User) {
        if (oauth2User instanceof OidcUser oidcUser) {
            log.info("GOOGLE ISSUED id_token='{}' expiresAt={}",
                    oidcUser.getIdToken().getTokenValue(), oidcUser.getIdToken().getExpiresAt());
        }
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("google", authentication.getName());
        if (authorizedClient != null) {
            log.info("GOOGLE ISSUED access_token='{}' expiresAt={} scopes={}",
                    authorizedClient.getAccessToken().getTokenValue(),
                    authorizedClient.getAccessToken().getExpiresAt(),
                    authorizedClient.getAccessToken().getScopes());
        }
    }

    private AppUser createGoogleUser(String email, OAuth2User oauth2User) {
        String username = deriveUniqueUsername(email);
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not seeded - see RoleSeeder"));
        AppUser user = AppUser.builder()
                .username(username)
                .email(email)
                // Unusable random password: this account only ever authenticates via Google.
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .roles(new HashSet<>(Set.of(userRole)))
                .authProvider("GOOGLE")
                .emailVerified(true)
                // Google already has these on file; seed them so profile basics aren't blank on first login.
                .displayName(oauth2User.getAttribute("name"))
                .avatarUrl(oauth2User.getAttribute("picture"))
                .termsAcceptedAt(Instant.now())
                .build();
        appUserRepository.save(user);
        log.info("GOOGLE ACCOUNT CREATED username='{}' email='{}'", username, email);
        return user;
    }

    private String deriveUniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        String candidate = base;
        int suffix = 1;
        while (appUserRepository.existsByUsername(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }
}
