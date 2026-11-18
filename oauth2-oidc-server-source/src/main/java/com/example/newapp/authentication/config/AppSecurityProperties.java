package com.example.newapp.authentication.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppSecurityProperties(
        String issuer,
        Security security,
        Oauth2 oauth2,
        GoogleLogin googleLogin
) {
    public record GoogleLogin(
            String clientId,
            String clientSecret
    ) {
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public record Security(
            int maxFailedLogins,
            int lockoutDurationMinutes
    ) {}

    public record Oauth2(
            List<ClientDefinition> clients
    ) {}

    public record ClientDefinition(
            String clientId,
            String clientSecret,
            boolean publicClient,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris
    ) {}
}
