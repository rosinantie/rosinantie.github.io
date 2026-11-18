package com.example.newapp.authentication.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Study aid: makes the Authorization Code + PKCE wire exchange visible in the log,
 * the same way SessionCookieLogger makes the HttpSession visible. Reads request
 * parameters only (no Spring Security internals), so it works regardless of which
 * version of the authorization server library is on the classpath.
 * <p>
 * code_verifier and client_secret are the actual secrets of this exchange and are
 * never logged by value - only whether they were present.
 */
@Slf4j
@Component
public class OAuth2FlowLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();

        switch (uri) {
            case "/oauth2/authorize" -> log.info(
                    "OAUTH2 AUTHORIZE REQUEST response_type='{}' client_id='{}' redirect_uri='{}' scope='{}' " +
                            "state='{}' code_challenge='{}' code_challenge_method='{}'",
                    request.getParameter("response_type"), request.getParameter("client_id"),
                    request.getParameter("redirect_uri"), request.getParameter("scope"),
                    request.getParameter("state"), request.getParameter("code_challenge"),
                    request.getParameter("code_challenge_method"));
            case "/oauth2/token" -> {
                String code = request.getParameter("code");
                boolean verifierPresent = hasText(request.getParameter("code_verifier"));
                log.info("OAUTH2 TOKEN REQUEST grant_type='{}' client_id='{}' redirect_uri='{}' code='{}' " +
                                "code_verifier_present={} (verifier value never logged - it's the PKCE proof)",
                        request.getParameter("grant_type"), request.getParameter("client_id"),
                        request.getParameter("redirect_uri"), maskCode(code), verifierPresent);
            }
            case "/oauth2/jwks" -> log.info(
                    "OAUTH2 JWKS REQUEST - caller is fetching the public key to verify a JWT's RS256 signature");
            default -> { /* not a flow endpoint we annotate */ }
        }

        chain.doFilter(request, response);

        if (uri.equals("/oauth2/authorize") && response.getStatus() / 100 == 3) {
            log.info("OAUTH2 AUTHORIZE RESPONSE {} -> Location: {}", response.getStatus(), response.getHeader("Location"));
        }
        if (uri.equals("/oauth2/token")) {
            log.info("OAUTH2 TOKEN RESPONSE status={} (200 = PKCE verifier matched the earlier challenge; " +
                    "400 invalid_grant = it didn't, code already used, or it expired)", response.getStatus());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String maskCode(String code) {
        if (code == null) {
            return null;
        }
        return code.substring(0, Math.min(8, code.length())) + "...(" + code.length() + " chars)";
    }
}
