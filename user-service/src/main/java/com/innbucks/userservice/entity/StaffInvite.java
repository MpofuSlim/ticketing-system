package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One single-use staff invite link (V44).
 *
 * <p>Only the SHA-256 of the token is stored ({@link #tokenHash}); the token is
 * 32 random bytes, so there is nothing to enumerate and a keyed hash would buy
 * nothing (the same reasoning as {@code refresh_tokens}). It is bound to the
 * address it was sent to ({@link #sentToEmail}), which must still equal
 * {@code users.email} when it is redeemed.
 *
 * <p>Consumed by ONE conditional UPDATE ({@code StaffInviteRepository.consume}),
 * so of two concurrent redemptions exactly one wins. Revoked on resend
 * ({@code SUPERSEDED}), deactivation ({@code DEACTIVATED}) and reactivation
 * ({@code REACTIVATED}).
 */
@Entity
@Table(name = "staff_invites")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffInvite {

    public static final String REVOKED_SUPERSEDED = "SUPERSEDED";
    public static final String REVOKED_DEACTIVATED = "DEACTIVATED";
    public static final String REVOKED_REACTIVATED = "REACTIVATED";
    public static final String REVOKED_EMAIL_CHANGED = "EMAIL_CHANGED";

    /** Delivery outcome, written by the invite mailer after commit. */
    public enum Delivery { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the raw {@code STI-} token. Never logged. */
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "sent_to_email", nullable = false, length = 254)
    private String sentToEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_email", nullable = false, length = 254)
    private String createdByEmail;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 32)
    private String revokedReason;

    @Column(name = "delivery_status", nullable = false, length = 16)
    @Builder.Default
    private String deliveryStatus = Delivery.PENDING.name();

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /** Live = not used, not revoked, not expired at {@code now}. */
    public boolean isLive(LocalDateTime now) {
        return usedAt == null && revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
