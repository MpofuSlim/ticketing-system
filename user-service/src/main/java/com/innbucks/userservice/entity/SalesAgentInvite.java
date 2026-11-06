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
import java.util.UUID;

/**
 * One single-use Foundry portal invite for a sales agent (V56).
 *
 * <p>Minted by event-service through {@code POST /users/internal/sales-agent-invites}
 * when an organizer gives a selling team member portal access; emailed to
 * {@link #email}; redeemed at {@code POST /auth/sales-agent-invite/accept}, which
 * binds an email and password to the InnBucks account on {@link #phoneNumber}.
 *
 * <p>Only the SHA-256 of the token is stored. Consumed by ONE conditional UPDATE
 * ({@code SalesAgentInviteRepository.consume}) that also stamps
 * {@link #accountUserUuid}, so of two concurrent redemptions exactly one wins and
 * {@code chk_sales_agent_invites_use_binds_account} holds. A new invite for the
 * same {@link #teamMemberId} revokes the earlier open ones, and so does
 * {@code DELETE /users/internal/sales-agent-invites/{teamMemberId}}.
 *
 * <p>Every write after the insert is a targeted UPDATE, never a save of a loaded
 * row, so a stale copy can never write {@code used_at} back to null.
 */
@Entity
@Table(name = "sales_agent_invites")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesAgentInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex of the raw {@code SAI-} token. Never logged. */
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** event-service's team row ({@code event_scanners.scanner_id}). */
    @Column(name = "team_member_id", nullable = false)
    private UUID teamMemberId;

    @Column(name = "organizer_uuid", nullable = false)
    private UUID organizerUuid;

    /** The organizer's business name as event-service sent it, flattened to one line and capped. */
    @Column(name = "organizer_name", length = 80)
    private String organizerName;

    /** E.164. */
    @ToString.Exclude
    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @ToString.Exclude
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** {@code users.user_uuid} of the account it was accepted into; set by the consume. */
    @Column(name = "account_user_uuid")
    private UUID accountUserUuid;

    /** Live = not used, not revoked, not expired at {@code now}. */
    public boolean isLive(LocalDateTime now) {
        return usedAt == null && revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
