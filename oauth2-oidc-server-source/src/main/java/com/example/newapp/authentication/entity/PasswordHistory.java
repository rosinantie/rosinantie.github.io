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
 * Archive of a user's previous password hashes (never the current one), so
 * PasswordHistoryService can enforce "can't reuse your last N passwords".
 * Kept as its own table rather than a field on AppUser since it's a growing,
 * prunable log, not account state.
 */
@Entity
@Table(name = "password_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant changedAt = Instant.now();
}
