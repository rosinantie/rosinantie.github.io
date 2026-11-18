---
layout: post
title: "Building an OAuth2/OIDC auth server, one verified phase at a time"
date: 2026-11-18
categories: tech
---

I already have a fully-featured Spring Authorization Server project — MFA, passkeys, rate limiting, Google login, the works. The problem: I built most of it in one sitting, which meant I could *run* it but couldn't fully explain every piece of it to myself. So instead of reading the finished code, I started a second, deliberately empty Spring Boot project (`new-app`) and rebuilt the same system from nothing, one phase at a time — never moving to the next piece until the current one was actually running against a real Postgres database and verified with real HTTP calls, not just "it compiled."

This post is the build log: what got added in what order, why each piece exists, and the bugs I actually hit along the way (there were real ones, not hypothetical "gotchas" — I'll call them out as they come up).

---

## Phase 0 — an empty shell

Started from nothing: `spring-boot-starter-parent`, Java 17, one dependency (`spring-boot-starter-web`), one `@SpringBootApplication` class. No security, no database, no templates. The point was to have something that runs before adding anything that could break.

## Phase 1 — entities, trimmed to what the phase actually needs

The finished project's `AppUser` entity has accumulated fields from nine different features: MFA secrets, WebAuthn user handles, email verification tokens, password-reset tokens, lockout counters. If I'd copied that entity wholesale on day one, I'd have had a dozen unused columns and no idea which ones mattered yet.

So phase 1's `AppUser` started bare — `id`, `username`, `email`, `password`, `roles`, `enabled`, `createdAt` — plus `Role` and `Permission` for RBAC instead of a single role string:

```java
@Entity
@Table(name = "roles")
@EqualsAndHashCode(of = "name")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();
}
```

A user holds a *set* of roles, and each role is a bundle of permissions — so `hasRole("ADMIN")`-style coarse checks and `hasAuthority("users:read")`-style fine-grained checks can both work off the same data, instead of baking one string into the user row.

I later ended up copying the finished project's *entire* entity set in one pass (`ActiveSession`, `LoginHistory`, `PasswordHistory`, `PasskeyCredential` too) rather than staying strictly incremental — partly impatience, partly because the entities themselves are cheap; it's the *services that use them* that carry the real learning value, and those got built one at a time regardless.

## Phase 2 — the security bridge: PasswordEncoderConfig, RoleSeeder, AppUserPrincipal

Three small pieces had to exist before login could work at all.

**`PasswordEncoderConfig`** — one bean, `BCryptPasswordEncoder`. Nothing hashes or verifies a password without this.

**`RoleSeeder`** — a `CommandLineRunner` that inserts `ROLE_USER`/`ROLE_ADMIN` and a handful of baseline permissions on startup, idempotently:

```java
@Component
@RequiredArgsConstructor
public class RoleSeeder implements CommandLineRunner {
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, Permission> permissions = ensurePermissions();
        ensureRole("ROLE_USER", "Standard authenticated user", List.of(
                permissions.get("profile:read"), permissions.get("profile:write")));
        ensureRole("ROLE_ADMIN", "Full administrative access", List.copyOf(permissions.values()));
    }
    // ensurePermission/ensureRole: findByName().orElseGet(() -> save(...))
}
```

Without this, registration would have no role to hand a new user — the DB starts genuinely empty.

**`AppUserPrincipal`** — the adapter between my JPA entity and Spring Security's `UserDetails` contract. Spring Security never talks to `AppUser` directly; it only ever calls `UserDetailsService.loadUserByUsername(...)`, which has to return a `UserDetails`. This class wraps the entity and answers on its behalf:

```java
@Override
public List<? extends GrantedAuthority> getAuthorities() {
    List<GrantedAuthority> authorities = new ArrayList<>();
    appUser.getRoleNames().forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
    appUser.getPermissionNames().forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
    return authorities;
}
```

Both role names *and* individual permission names become authorities, so `hasRole(...)` and `hasAuthority(...)` both work against the same principal, no extra plumbing.

The four account-status methods (`isAccountNonExpired`, `isAccountNonLocked`, `isCredentialsNonExpired`, `isEnabled`) are Spring Security's gates, checked on every login *before* the password is even compared. I deliberately trimmed two of them versus the finished project:

- `isCredentialsNonExpired()` is hardcoded `true` — the real check depends on a password-expiry policy I hadn't built yet
- `isEnabled()` only checks `appUser.isEnabled()`, dropping the finished project's `&& appUser.isEmailVerified()` — since `emailVerified` defaults to `false` and there's no email-verification feature yet, keeping that check would have permanently locked out every user I registered

Both are exactly the kind of thing that's invisible until you actually try to log in and nothing happens.

## Phase 3 — wiring it live: DefaultSecurityConfig

```java
@Bean
public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/login", "/register", "/error", "/webjars/**", "/css/**", "/js/**")
                    .permitAll()
                    .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").permitAll())
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll());
    return http.build();
}
```

This is the one bean that actually *activates* everything from phase 2 — without it, Spring Security falls back to its own generated in-memory user (a random password printed to the console on every startup). `.formLogin(...)` turns on the built-in `UsernamePasswordAuthenticationFilter`, which is why I never write a `@PostMapping("/login")` myself — it intercepts the POST, calls `AppUserDetailsService`, checks the password, done.

Then a bare `RegistrationController` + two unstyled Thymeleaf forms, and the first real end-to-end test: register a user via `curl`, confirm the CSRF token auto-injects into the form (Spring's `RequestDataValueProcessor` wiring into Thymeleaf), log in with the same credentials, land on a protected `/` page. Checked the database directly — real bcrypt hash, real `ROLE_USER` row in the join table. First working slice.

## Phase 4 — turning it into an actual OAuth2/OIDC server

This is the part that turns "a website with a login" into "a server other applications can get tokens from." `AuthorizationServerConfig` is a second `SecurityFilterChain`, `@Order(1)` (checked first), restricted by the library to exactly the OAuth2/OIDC endpoints:

```java
@Bean
@Order(1)
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
    http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .oidc(Customizer.withDefaults());
    http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
            .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
    return http.build();
}
```

`applyDefaultSecurity` registers the actual protocol filters (generating codes, exchanging them for tokens) — none of that logic is mine. `.oidc(...)` is one line with a large effect: it's the *entire* difference between plain OAuth2 (just access tokens) and OIDC on top (`id_token`, `/userinfo`, the `/.well-known/openid-configuration` discovery document). `.oauth2ResourceServer(jwt(...))` makes this same app able to validate the Bearer tokens it hands out, which is what lets `/userinfo` work at all.

Registered clients come from config, not hardcoded Java:

```yaml
app:
  oauth2:
    clients:
      - client-id: demo-public-client
        public-client: true
        redirect-uris: [http://127.0.0.1:9600/authorized]
      - client-id: postman-test-client
        client-secret: "postman-secret-change-me"
        public-client: false
        redirect-uris: [https://oauth.pstmn.io/v1/callback]
```

mapped onto a typed `AppSecurityProperties` record via `@ConfigurationProperties(prefix = "app")` (this only works because `@ConfigurationPropertiesScan` is on the main class — easy to forget, impossible to debug if you do). Public clients get `ClientAuthenticationMethod.NONE` and rely entirely on PKCE (`requireProofKey(true)`); confidential clients get a bcrypt-hashed secret and `CLIENT_SECRET_BASIC`.

A `jwtCustomizer` bean is the only reason the issued JWT carries anything about *who* logged in — by default a token only has the bare minimum:

```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
    return context -> {
        if (!"access_token".equals(context.getTokenType().getValue())) return;
        if (!(context.getPrincipal().getPrincipal() instanceof AppUserPrincipal p)) return;
        AppUser appUser = p.getAppUser();
        context.getClaims().claims(claims -> {
            claims.put("roles", appUser.getRoleNames());
            claims.put("permissions", appUser.getPermissionNames());
            claims.put("email", appUser.getEmail());
            claims.put("preferred_username", appUser.getUsername());
        });
    };
}
```

And a fresh 2048-bit RSA key pair gets generated on every startup for RS256 signing (private key signs, public key gets published at `/oauth2/jwks`) — deliberately not persisted, which is the right simplification for a study project and the wrong one for anything real, since it means every token minted before a restart becomes unverifiable the moment the process comes back up.

**The actual test**: generated a PKCE `code_verifier`/`code_challenge` pair by hand, logged in, drove `/oauth2/authorize` → got a code → exchanged it at `/oauth2/token` → got back a real signed JWT. Decoded all three parts manually:

```
header:    {"kid": "d4285423-...", "alg": "RS256"}
payload:   {"sub": "demo_user", "roles": ["ROLE_USER"], "permissions": [...], "email": "...", ...}
signature: <raw bytes, not decodable JSON — nothing to read, just a check>
```

One real mistake along the way: tried exchanging the code using HTTP Basic auth for the *public* client (`curl -u "demo-public-client:"`) and got a `403`. Public clients don't authenticate that way at all — `client_id` goes as a plain form field, no `Authorization` header, since there's no secret to send. The fix was one line, but it's the kind of thing you only learn by actually hitting it.

## Phase 5 — making the invisible visible: three logging classes

Once the OAuth2 flow worked, I wanted to actually *see* the session/CSRF/token lifecycle instead of trusting it blindly. Three study-aid classes, each hooking a different layer:

- **`SessionCookieLogger`** implements `HttpSessionListener` + `HttpSessionAttributeListener` — Spring Boot auto-registers any bean implementing a servlet listener interface. Logs the exact moment Tomcat creates a session (the same moment the `JSESSIONID` cookie goes out) and every value written into it.
- **`OAuth2FlowLoggingFilter`** — reads request parameters on `/oauth2/authorize` and `/oauth2/token` and logs them, explicitly *never* logging `code_verifier` or `client_secret` by value, only whether they were present.
- **`AuthenticationEventLogger`** listens for Spring Security's `AuthenticationSuccessEvent`/`AbstractAuthenticationFailureEvent`, filtered to the JWT-specific ones — the only hook into seeing a Bearer token's signature/expiry validation actually happen, since that logic lives entirely inside library code otherwise.

**The bug I found while verifying this**: I wired `OAuth2FlowLoggingFilter` with `.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)`. It worked for `/oauth2/token` but silently produced *zero* log lines for `/oauth2/authorize`, despite the request itself succeeding. Turned out `OAuth2AuthorizationEndpointFilter` sits *earlier* in Spring Security's filter order than that marker — so the authorize endpoint had already redirected the request before my filter ever got a turn. It only "worked" for the token endpoint by coincidence of where that filter happens to sit relative to the same marker. Fixed by anchoring to `SecurityContextHolderFilter` instead — one of the first filters in any chain, with a guaranteed registered order (tried anchoring to `OAuth2AuthorizationEndpointFilter.class` directly first, which threw `BeanCreationException: ... does not have a registered order` — that class isn't in Spring Security's default ordering table at all unless it's already been added to *this specific* chain).

## Phase 6 — login hardening: lockout + history

Two entities (`LoginHistory`, `ActiveSession`) had existed unused since phase 1. `LoginAttemptService` finally gave them a reason to exist:

```java
public void onFailedLogin(String username) {
    appUserRepository.findByUsername(username).ifPresentOrElse(user -> {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= properties.security().maxFailedLogins()) {
            user.setLockedUntil(Instant.now().plus(properties.security().lockoutDurationMinutes(), ChronoUnit.MINUTES));
        }
        appUserRepository.save(user);
    }, () -> log.warn("Failed login attempt for unknown username='{}'", username));
}
```

`AppUserPrincipal.isAccountNonLocked()` had been checking `AppUser.isLocked()` since phase 2 — it just had nothing setting `lockedUntil`, so it silently always returned `true`. Wiring this service into a `LockoutAwareFailureHandler` (and a `LoginSuccessHandler` that resets the counter and records history on success) is what gave that check its teeth. Both handlers are trimmed from the finished project's versions — no password-expiry branch in the failure handler, no MFA-redirect branch in the success handler, since neither feature exists in `new-app` yet. Tested by deliberately failing a login 5 times in a row and watching the account actually lock.

## Phase 7 — social login

`SocialLoginConfig` builds Google's `ClientRegistration` programmatically instead of relying on Spring Boot's auto-configuration, specifically so the app can start cleanly with Google *unconfigured*:

```java
@Bean
public ClientRegistrationRepository clientRegistrationRepository() {
    if (properties.googleLogin().isConfigured()) {
        return new InMemoryClientRegistrationRepository(/* build Google's ClientRegistration */);
    }
    // InMemoryClientRegistrationRepository rejects an empty list, so return a
    // trivial repository with zero registrations instead.
    return registrationId -> null;
}
```

`GoogleLoginSuccessHandler` is the interesting part: Google only proves *who* someone is — it still has to become a real `AppUser` row so the rest of the server (JWT claims, lockout, login history) treats every login path identically:

```java
AppUser user = appUserRepository.findByEmail(email).orElseGet(() -> createGoogleUser(email, oauth2User));
AppUserPrincipal principal = new AppUserPrincipal(user);
Authentication localAuth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
loginSuccessHandler.onAuthenticationSuccess(request, response, localAuth);
```

First login via Google creates the account on the fly, with a random unusable password (`authProvider = "GOOGLE"`) and `emailVerified = true` straight away, since Google already vouches for the address.

**Two real bugs here, both worth remembering:**

1. The comment I originally copied from the finished project claimed visiting `/oauth2/authorization/google` while Google is unconfigured "just 404s instead of crashing the whole app." Tested it directly: it's actually a `500` (`InvalidClientRegistrationIdException`), not a `404`. Harmless in practice since the "Continue with Google" link is itself hidden whenever this branch is active — but worth correcting a comment once you've actually verified it's wrong rather than carrying forward an assumption.
2. `redirect_uri_mismatch` from Google on the very first real attempt — because the exact same OAuth client ID was already registered for a *different* app running on port 9500, with only that port's redirect URI whitelisted in Google Cloud Console. Had to add `http://localhost:9600/login/oauth2/code/google` as a second authorized redirect URI for the same client before this app's callback would be accepted. A good reminder that `{baseUrl}/login/oauth2/code/{registrationId}` resolving correctly on *my* side says nothing about whether Google's side has been told to expect it.

Also added `spring-dotenv` + a gitignored `.env` file so `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` never have to be exported into shell history or hardcoded into `application-local.yml` — the same pattern the finished project already used.


---

## Full source

Everything above walks through *why*. The actual, current state of every file in `new-app` lives here, grouped the same way the project is packaged:

**Build**
- [`pom.xml`](/oauth2-oidc-server-source/pom.xml)

**Entry point**
- [`src/main/java/com/example/newapp/NewAppApplication.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/NewAppApplication.java)

**Config**
- [`src/main/java/com/example/newapp/authentication/config/AppSecurityProperties.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/AppSecurityProperties.java)
- [`src/main/java/com/example/newapp/authentication/config/AuthorizationServerConfig.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/AuthorizationServerConfig.java)
- [`src/main/java/com/example/newapp/authentication/config/DefaultSecurityConfig.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/DefaultSecurityConfig.java)
- [`src/main/java/com/example/newapp/authentication/config/PasswordEncoderConfig.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/PasswordEncoderConfig.java)
- [`src/main/java/com/example/newapp/authentication/config/RoleSeeder.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/RoleSeeder.java)
- [`src/main/java/com/example/newapp/authentication/config/SocialLoginConfig.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/config/SocialLoginConfig.java)

**Entities**
- [`src/main/java/com/example/newapp/authentication/entity/ActiveSession.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/ActiveSession.java)
- [`src/main/java/com/example/newapp/authentication/entity/AppUser.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/AppUser.java)
- [`src/main/java/com/example/newapp/authentication/entity/LoginHistory.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/LoginHistory.java)
- [`src/main/java/com/example/newapp/authentication/entity/PasskeyCredential.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/PasskeyCredential.java)
- [`src/main/java/com/example/newapp/authentication/entity/PasswordHistory.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/PasswordHistory.java)
- [`src/main/java/com/example/newapp/authentication/entity/Permission.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/Permission.java)
- [`src/main/java/com/example/newapp/authentication/entity/Role.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/entity/Role.java)

**Repositories**
- [`src/main/java/com/example/newapp/authentication/repository/ActiveSessionRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/ActiveSessionRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/AppUserRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/AppUserRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/LoginHistoryRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/LoginHistoryRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/PasskeyCredentialRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/PasskeyCredentialRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/PasswordHistoryRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/PasswordHistoryRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/PermissionRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/PermissionRepository.java)
- [`src/main/java/com/example/newapp/authentication/repository/RoleRepository.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/repository/RoleRepository.java)

**Security**
- [`src/main/java/com/example/newapp/authentication/security/AppUserPrincipal.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/AppUserPrincipal.java)
- [`src/main/java/com/example/newapp/authentication/security/AuthenticationEventLogger.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/AuthenticationEventLogger.java)
- [`src/main/java/com/example/newapp/authentication/security/GoogleLoginSuccessHandler.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/GoogleLoginSuccessHandler.java)
- [`src/main/java/com/example/newapp/authentication/security/LockoutAwareFailureHandler.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/LockoutAwareFailureHandler.java)
- [`src/main/java/com/example/newapp/authentication/security/LoginSuccessHandler.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/LoginSuccessHandler.java)
- [`src/main/java/com/example/newapp/authentication/security/OAuth2FlowLoggingFilter.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/OAuth2FlowLoggingFilter.java)
- [`src/main/java/com/example/newapp/authentication/security/SessionCookieLogger.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/security/SessionCookieLogger.java)

**Services**
- [`src/main/java/com/example/newapp/authentication/service/AppUserDetailsService.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/service/AppUserDetailsService.java)
- [`src/main/java/com/example/newapp/authentication/service/LoginAttemptService.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/service/LoginAttemptService.java)
- [`src/main/java/com/example/newapp/authentication/service/LoginHistoryService.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/service/LoginHistoryService.java)

**Web**
- [`src/main/java/com/example/newapp/authentication/web/PageController.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/web/PageController.java)
- [`src/main/java/com/example/newapp/authentication/web/RegistrationController.java`](/oauth2-oidc-server-source/src/main/java/com/example/newapp/authentication/web/RegistrationController.java)

**Resources & templates**
- [`src/main/resources/application.yml`](/oauth2-oidc-server-source/src/main/resources/application.yml)
- [`src/main/resources/application-local.yml`](/oauth2-oidc-server-source/src/main/resources/application-local.yml)
- [`src/main/resources/templates/login.html`](/oauth2-oidc-server-source/src/main/resources/templates/login.html)
- [`src/main/resources/templates/register.html`](/oauth2-oidc-server-source/src/main/resources/templates/register.html)
- [`src/main/resources/templates/home.html`](/oauth2-oidc-server-source/src/main/resources/templates/home.html)

**Test**
- [`src/test/java/com/example/newapp/NewAppApplicationTests.java`](/oauth2-oidc-server-source/src/test/java/com/example/newapp/NewAppApplicationTests.java)
