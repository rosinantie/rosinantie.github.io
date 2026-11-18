package com.example.newapp.authentication.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Logs every Bearer-JWT validation Spring Security performs. This is the only place
 * a presented access token's actual validation (signature, expiry, claims) can be
 * observed in this codebase - it all happens inside library code (JwtAuthenticationProvider)
 * with no hook of our own to log from otherwise. Spring Security publishes an
 * ApplicationEvent for every authentication attempt; we just listen for the JWT-specific ones.
 * <p>
 * In this app, that only ever fires for requests matched by AuthorizationServerConfig's
 * chain (e.g. /userinfo) - plain session logins never go through here.
 */
@Slf4j
@Component
public class AuthenticationEventLogger {

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
            log.info("BEARER TOKEN VALIDATED subject='{}' expiresAt={} claims={}",
                    jwtAuth.getToken().getSubject(), jwtAuth.getToken().getExpiresAt(), jwtAuth.getToken().getClaims());
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        if (event.getAuthentication() instanceof BearerTokenAuthenticationToken) {
            log.warn("BEARER TOKEN REJECTED reason={}: {}",
                    event.getException().getClass().getSimpleName(), event.getException().getMessage());
        }
    }
}
