package com.example.newapp.authentication.config;

import com.example.newapp.authentication.entity.AppUser;
import com.example.newapp.authentication.security.AppUserPrincipal;
import com.example.newapp.authentication.security.OAuth2FlowLoggingFilter;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * The actual OAuth2/OIDC authorization server: PKCE-enforced auth-code flow,
 * RS256-signed JWT access tokens, rotating refresh tokens.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AuthorizationServerConfig {

  private final AppSecurityProperties properties;
  private final PasswordEncoder passwordEncoder;
  private final OAuth2FlowLoggingFilter oAuth2FlowLoggingFilter;

  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    log.info(
        "BUILDING authorization-server filter chain (/oauth2/authorize, /oauth2/token, /oauth2/jwks, /userinfo, /.well-known/**)");
    OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
    http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
        .oidc(Customizer.withDefaults());

    http
        .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
            new LoginUrlAuthenticationEntryPoint("/login"),
            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        // study aid: logs the authorize/token wire parameters - see
        // OAuth2FlowLoggingFilter.
        // Anchored before SecurityContextHolderFilter (one of the first filters in any
        // chain, always has a registered order) rather than
        // UsernamePasswordAuthenticationFilter:
        // OAuth2AuthorizationEndpointFilter sits earlier than that marker and would
        // otherwise
        // redirect /oauth2/authorize requests before our filter ever got a turn.
        .addFilterBefore(oAuth2FlowLoggingFilter, SecurityContextHolderFilter.class);

    return http.build();
  }

  /**
   * In-memory authorization state (pending/issued authorization codes and token
   * records). Lost on every restart - fine for now; swap for
   * JdbcOAuth2AuthorizationService if this needs to survive restarts.
   */
  @Bean
  public OAuth2AuthorizationService authorizationService() {
    return new InMemoryOAuth2AuthorizationService();
  }

  @Bean
  public RegisteredClientRepository registeredClientRepository() {
    RegisteredClient[] clients = properties.oauth2().clients().stream()
        .map(this::toRegisteredClient)
        .toArray(RegisteredClient[]::new);
    log.info("LOADED {} registered OAuth2 client(s) into InMemoryRegisteredClientRepository", clients.length);
    return new InMemoryRegisteredClientRepository(clients);
  }

  private RegisteredClient toRegisteredClient(AppSecurityProperties.ClientDefinition def) {
    log.info("REGISTERING OAuth2 client clientId='{}' publicClient={} redirectUris={}",
        def.clientId(), def.publicClient(), def.redirectUris());
    RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId(def.clientId())
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .scope(OidcScopes.OPENID)
        .scope(OidcScopes.PROFILE)
        .scope(OidcScopes.EMAIL)
        .scope("offline_access")
        .clientSettings(ClientSettings.builder()
            .requireAuthorizationConsent(false)
            .requireProofKey(true) // PKCE required even for confidential clients: defense in depth
            .build())
        .tokenSettings(TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofMinutes(15))
            .refreshTokenTimeToLive(Duration.ofDays(7))
            .reuseRefreshTokens(false) // rotation: each refresh issues a new refresh token
            .build());

    def.redirectUris().forEach(builder::redirectUri);
    if (def.postLogoutRedirectUris() != null) {
      def.postLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
    }

    if (def.publicClient()) {
      builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
    } else {
      builder
          .clientSecret(passwordEncoder.encode(def.clientSecret()))
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    return builder.build();
  }

  /**
   * Adds roles/permissions/email/username claims to the access token so resource
   * servers don't need a user lookup. roles and permissions are separate claims
   * (not the combined authorities list) so a resource server can check either
   * hasRole("ADMIN")-style coarse access or specific permissions like
   * "users:read".
   */
  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
    return context -> {
      if (!"access_token".equals(context.getTokenType().getValue())) {
        return;
      }
      if (!(context.getPrincipal().getPrincipal() instanceof AppUserPrincipal appUserPrincipal)) {
        return;
      }
      AppUser appUser = appUserPrincipal.getAppUser();
      log.info("CUSTOMIZING access_token claims for username='{}' roles={} permissions={} client='{}'",
          appUser.getUsername(), appUser.getRoleNames(), appUser.getPermissionNames(),
          context.getRegisteredClient().getClientId());
      context.getClaims().claims(claims -> {
        claims.put("roles", appUser.getRoleNames());
        claims.put("permissions", appUser.getPermissionNames());
        claims.put("email", appUser.getEmail());
        claims.put("preferred_username", appUser.getUsername());
      });
    };
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource() {
    log.info("Generating RSA key pair (2048-bit) for JWT signing (fresh on every restart, not persisted)");
    KeyPair keyPair = generateRsaKey();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
    RSAKey rsaKey = new RSAKey.Builder(publicKey)
        .privateKey(privateKey)
        .keyID(UUID.randomUUID().toString())
        .build();
    log.info("Generated RSA key pair for JWT signing: keyId='{}' algorithm=RS256 keySize=2048", rsaKey.getKeyID());
    return new ImmutableJWKSet<>(new JWKSet(rsaKey));
  }

  private static KeyPair generateRsaKey() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      return keyPairGenerator.generateKeyPair();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to generate RSA key pair for JWT signing", ex);
    }
  }

  @Bean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    log.info(
        "BUILDING JwtDecoder: verifies incoming Bearer tokens' RS256 signature against the public key in jwkSource");
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }

  @Bean
  public AuthorizationServerSettings authorizationServerSettings() {
    log.info("Authorization server issuer='{}'", properties.issuer());
    return AuthorizationServerSettings.builder()
        .issuer(properties.issuer())
        .build();
  }
}
