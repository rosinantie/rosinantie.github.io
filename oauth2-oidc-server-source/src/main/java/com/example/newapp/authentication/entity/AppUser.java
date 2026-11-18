package com.example.newapp.authentication.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /** Shown in the UI in place of the username; falls back to username when null. */
    private String displayName;

    private String avatarUrl;

    /** IANA zone id, e.g. "Asia/Kolkata". Null means "unset / use server default". */
    private String timezone;

    /** BCP 47 language tag, e.g. "en-US". Null means "unset / use server default". */
    private String locale;

    /**
     * When the user accepted the current Terms of Service / Privacy Policy. Null only for
     * rows created before this field existed; every registration from here on sets it.
     */
    private Instant termsAcceptedAt;

    /** GDPR-style opt-in (not opt-out): null/false both mean "no consent on file". */
    private Boolean marketingConsent;

    @Builder.Default
    private Instant passwordChangedAt = Instant.now();

    /**
     * Many-to-many, not a single role string: a user can hold several roles, and
     * roles themselves are bundles of Permissions (see Role, Permission, RoleSeeder).
     * EAGER for the same reason backupCodeHashes is: this entity sits inside
     * AppUserPrincipal across the whole HTTP session, with no guaranteed open
     * persistence context on later requests to lazily resolve it.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_user_roles",
            joinColumns = @JoinColumn(name = "app_user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    /** "LOCAL" (username/password) or "GOOGLE" (created via Google social login). Informational only. */
    @Column(nullable = false)
    @Builder.Default
    private String authProvider = "LOCAL";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Set while a verification email is outstanding; cleared once the link is used. */
    @Column(unique = true)
    private String verificationToken;

    private Instant verificationTokenExpiry;

    /** Set while a password-reset email is outstanding; cleared once the link is used (or a new one is requested). */
    @Column(unique = true)
    private String resetToken;

    private Instant resetTokenExpiry;

    @Column(nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    /**
     * The WebAuthn "user handle": an opaque random id a passkey is bound to, distinct from the
     * username so a credential never leaks the username to the authenticator/platform UI. Null
     * until the first passkey registration or login attempt lazily provisions one.
     */
    @Column(unique = true)
    private byte[] webauthnUserHandle;

    /** AES-encrypted TOTP secret (base32 plaintext before encryption). Null until MFA is enrolled. */
    @Column(length = 512)
    private String mfaSecretEncrypted;

    /**
     * Hashed one-time recovery codes, issued alongside TOTP enrollment so a lost
     * authenticator doesn't permanently lock the account out of MFA. Each is removed
     * the moment it's used. Never stores the plaintext codes.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_backup_codes", joinColumns = @JoinColumn(name = "app_user_id"))
    @Column(name = "code_hash", nullable = false)
    @Builder.Default
    private List<String> backupCodeHashes = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    private Instant lockedUntil;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean hasMarketingConsent() {
        return Boolean.TRUE.equals(marketingConsent);
    }

    public Set<String> getRoleNames() {
        return roles.stream().map(Role::getName).collect(Collectors.toSet());
    }

    public Set<String> getPermissionNames() {
        return roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getName)
                .collect(Collectors.toSet());
    }

    /** maxAgeDays <= 0 means the policy is off (never expires). */
    public boolean isPasswordExpired(int maxAgeDays) {
        if (maxAgeDays <= 0) {
            return false;
        }
        Instant changedAt = passwordChangedAt != null ? passwordChangedAt : createdAt;
        return changedAt.plus(maxAgeDays, ChronoUnit.DAYS).isBefore(Instant.now());
    }
}
