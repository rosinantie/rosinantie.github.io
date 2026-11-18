package com.example.newapp.authentication.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Builds the Google client registration programmatically from our own
 * app.google-login.* properties instead of Spring Boot's
 * spring.security.oauth2.client.registration.* auto-configuration.
 *
 * Why: Boot's auto-configuration validates client-id/client-secret as
 * non-blank and throws at startup if they're empty. Since Google login here
 * is optional (no Google Cloud project required just to run the server),
 * this builds an empty registration repository instead of failing to start
 * when those two env vars aren't set.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SocialLoginConfig {

    private final AppSecurityProperties properties;

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        if (properties.googleLogin().isConfigured()) {
            // CommonOAuth2Provider was removed from Spring Security; Google's OAuth
            // endpoints are stable and documented, so they're hardcoded here instead
            // of fetching https://accounts.google.com/.well-known/openid-configuration
            // at startup.
            ClientRegistration google = ClientRegistration.withRegistrationId("google")
                    .clientId(properties.googleLogin().clientId())
                    .clientSecret(properties.googleLogin().clientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                    .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                    .issuerUri("https://accounts.google.com")
                    .clientName("Google")
                    .build();
            log.info("Google login ENABLED");
            return new InMemoryClientRegistrationRepository(google);
        }

        log.info("Google login DISABLED (app.google-login.client-id/client-secret not set)");
        // InMemoryClientRegistrationRepository rejects an empty list, so for the "not
        // configured" case we return a trivial repository with zero registrations: the
        // app still starts fine either way. Directly visiting /oauth2/authorization/google
        // while disabled throws InvalidClientRegistrationIdException (a 500, not a 404) -
        // but the login page's "Continue with Google" link is itself hidden whenever this
        // branch is active (see PageController/login.html's googleLoginEnabled check), so
        // a user never reaches that URL through the normal UI.
        return registrationId -> null;
    }
}
