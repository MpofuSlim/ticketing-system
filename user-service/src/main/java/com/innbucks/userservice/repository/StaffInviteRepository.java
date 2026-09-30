package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.StaffInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StaffInviteRepository extends JpaRepository<StaffInvite, Long> {

    Optional<StaffInvite> findByTokenHash(String tokenHash);

    /**
     * SPENDS an invite: one conditional UPDATE, in the {@code OtpRepository.consume}
     * shape. Postgres serialises two concurrent redemptions on the row lock and the
     * second re-evaluates the WHERE against the first one's committed row, so
     * exactly one of them gets 1 — the caller requires exactly 1.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE StaffInvite i SET i.usedAt = :now WHERE i.tokenHash = :hash "
            + "AND i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now")
    int consume(@Param("hash") String tokenHash, @Param("now") LocalDateTime now);

    /** Revokes every live invite of an account (resend, deactivation, reactivation, accept). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE StaffInvite i SET i.revokedAt = :now, i.revokedReason = :reason "
            + "WHERE i.userId = :userId AND i.usedAt IS NULL AND i.revokedAt IS NULL")
    int revokeLive(@Param("userId") Long userId, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** The live invite, if any — what an INVITED row shows the console. */
    @Query("SELECT i FROM StaffInvite i WHERE i.userId = :userId AND i.usedAt IS NULL AND i.revokedAt IS NULL "
            + "ORDER BY i.createdAt DESC, i.id DESC")
    List<StaffInvite> findLive(@Param("userId") Long userId);

    /** Invites minted for an account since {@code since} — the resend quota. */
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime since);

    /** The oldest invite in the window — when the resend quota next frees a slot. */
    Optional<StaffInvite> findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, LocalDateTime since);

    /**
     * Records the delivery outcome — a targeted two-column UPDATE, never a
     * load-modify-save, because it runs on the mail thread after the request
     * committed and must not write a stale snapshot of the rest of the row back.
     */
    @Modifying
    @Query("UPDATE StaffInvite i SET i.deliveryStatus = :status, i.deliveredAt = :at WHERE i.id = :id")
    int markDelivery(@Param("id") Long id, @Param("status") String status, @Param("at") LocalDateTime at);
}
