package com.example.newapp.authentication.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per login attempt (success or failure), kept separate from AppUser so
 * history survives regardless of what happens to the account afterward, and so
 * a user can answer "was this really me logging in from Mumbai at 3am?".
 */
@Entity
@Table(name = "login_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean success;

    /** PASSWORD, GOOGLE, MFA_TOTP, MFA_BACKUP_CODE, or the failure's exception/reason code. */
    @Column(nullable = false)
    private String eventType;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();
}
