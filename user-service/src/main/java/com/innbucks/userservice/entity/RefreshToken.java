package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens",
        indexes = {
                @Index(name = "uk_refresh_tokens_hash", columnList = "token_hash", unique = true),
                @Index(name = "idx_refresh_tokens_family", columnList = "family_id"),
                @Index(name = "idx_refresh_tokens_user", columnList = "user_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Shared by every token in a rotation chain. Revoking the family kills them all. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** The row this token was minted to replace. Null for the first token in a family. */
    @Column(name = "parent_id")
    private UUID parentId;

    /** Set when this token is rotated out (points at its successor). */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null once the token has been used or its family was revoked. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * SHA-256 of the FE-supplied {@code X-Device-Id} header at issuance.
     * Null for rows minted before device-binding was rolled out (legacy
     * sessions; not enforced on rotate). Once non-null, the rotate path
     * compares against the hash of the incoming X-Device-Id and treats
     * a mismatch as token theft — same family-revoke side effect as a
     * replayed token.
     */
    @Column(name = "device_id_hash", length = 64)
    private String deviceIdHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
