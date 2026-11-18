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
 * One row per issued refresh token ("device session"), so a user can see
 * everywhere they're signed in and revoke one without touching the others -
 * the data behind the "view/revoke other devices" feature.
 *
 * authorizationId links back to the OAuth2Authorization held by
 * OAuth2AuthorizationService, which is what actually gets revoked; the raw
 * refresh token value is deliberately never stored here, only this opaque
 * internal id is at rest.
 */
@Entity
@Table(name = "active_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String authorizationId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String deviceName;

    private String ipAddress;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant lastUsedAt;
}
