package com.ticketflow.identity.sessions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One refresh token, stored as a hash (table "refresh_tokens"). */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set when this token was exchanged for the next one. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** Set when the whole family was revoked (logout, or reuse of a used token). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Long version;

    protected RefreshToken() {
        // for JPA
    }

    RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getFamilyId() {
        return familyId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    boolean isUsed() {
        return usedAt != null;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    void markUsed(Instant now) {
        this.usedAt = now;
    }
}
