package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.SalesAgentInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SalesAgentInviteRepository extends JpaRepository<SalesAgentInvite, Long> {

    Optional<SalesAgentInvite> findByTokenHash(String tokenHash);

    /**
     * SPENDS an invite and names the account it was accepted into, in one
     * conditional UPDATE (the {@code StaffInviteRepository.consume} shape).
     * Postgres serialises two concurrent redemptions on the row lock and the
     * second re-evaluates the WHERE against the first one's committed row, so
     * exactly one gets 1. Clears the persistence context: anything read before
     * it must be read again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SalesAgentInvite i SET i.usedAt = :now, i.accountUserUuid = :account "
            + "WHERE i.tokenHash = :hash AND i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now")
    int consume(@Param("hash") String tokenHash, @Param("account") UUID accountUserUuid,
                @Param("now") LocalDateTime now);

    /**
     * Revokes every live invite of a team member: a newer invite supersedes them,
     * and event-service's portal-access removal ends them. Never touches a used
     * or an expired one (the count is of links that stopped working), and never
     * an account.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SalesAgentInvite i SET i.revokedAt = :now WHERE i.teamMemberId = :teamMemberId "
            + "AND i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now")
    int revokeLive(@Param("teamMemberId") UUID teamMemberId, @Param("now") LocalDateTime now);
}
