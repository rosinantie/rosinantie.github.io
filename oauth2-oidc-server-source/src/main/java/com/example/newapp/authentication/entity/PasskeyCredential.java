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
 * A WebAuthn CredentialRecord (W3C term) persisted for one AppUser. Mirrors every field Spring
 * Security's CredentialRecord interface exposes, so a JpaUserCredentialRepository can round-trip
 * one without losing data - userEntityUserId points at AppUser.webauthnUserHandle, not AppUser.id,
 * matching what the WebAuthn ceremony itself uses.
 */
@Entity
@Table(name = "passkey_credential")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private byte[] credentialId;

    @Column(nullable = false)
    private byte[] userEntityUserId;

    @Column(nullable = false)
    private byte[] publicKeyCose;

    @Column(nullable = false)
    private long signatureCount;

    @Column(nullable = false)
    private boolean uvInitialized;

    /** Comma-joined AuthenticatorTransport values (e.g. "internal,hybrid"); never null, may be empty. */
    @Column(nullable = false)
    private String transports;

    @Column(nullable = false)
    private boolean backupEligible;

    @Column(nullable = false)
    private boolean backupState;

    private byte[] attestationObject;

    private byte[] attestationClientDataJSON;

    /** User-assigned name shown in the passkey management UI, e.g. "MacBook Touch ID". */
    @Column(nullable = false)
    private String label;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastUsed;
}
