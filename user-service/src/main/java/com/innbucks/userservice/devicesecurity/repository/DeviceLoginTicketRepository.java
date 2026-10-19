package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.LoginOutcome;
import com.innbucks.userservice.devicesecurity.entity.DeviceLoginTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DeviceLoginTicketRepository extends JpaRepository<DeviceLoginTicket, String> {

    /**
     * Spends a ticket. One statement, so two concurrent redemptions of the same
     * ticket cannot both see "unused" — exactly one gets 1 row back.
     */
    @Modifying
    @Query("""
            update DeviceLoginTicket t set t.redeemedAt = :now
            where t.jti = :jti and t.redeemedAt is null and t.voidedAt is null and t.expiresAt > :now
            """)
    int redeem(@Param("jti") String jti, @Param("now") LocalDateTime now);

    /** Who a ticket is for, read without loading (and caching) the ticket entity. */
    @Query("""
            select new com.innbucks.userservice.devicesecurity.repository.TicketRef(
                t.msisdn, t.customerDeviceId, t.installIdHash, t.issuedAt)
            from DeviceLoginTicket t where t.jti = :jti
            """)
    Optional<TicketRef> findRef(@Param("jti") String jti);

    /**
     * Records the broker's login result once. A compare-and-set rather than a load and
     * save: the ticket has no version, so saving a copy read before a concurrent void
     * would write {@code voided_at} back to null.
     */
    @Modifying
    @Query("""
            update DeviceLoginTicket t set t.outcome = :outcome, t.outcomeAt = :now, t.stagingCode = :code
            where t.jti = :jti and t.outcome is null
            """)
    int recordOutcome(@Param("jti") String jti, @Param("outcome") LoginOutcome outcome,
                      @Param("now") LocalDateTime now, @Param("code") String code);

    /** Voids one unspent ticket (redeem refused it because its phone is no longer signed in). */
    @Modifying
    @Query("""
            update DeviceLoginTicket t set t.voidedAt = :now
            where t.jti = :jti and t.voidedAt is null and t.redeemedAt is null
            """)
    int voidUnspent(@Param("jti") String jti, @Param("now") LocalDateTime now);

    /** A block, ban or removal voids the device's unspent tickets so an in-flight login cannot complete. */
    @Modifying
    @Query("""
            update DeviceLoginTicket t set t.voidedAt = :now
            where t.customerDeviceId = :deviceId and t.outcome is null and t.voidedAt is null and t.expiresAt > :now
            """)
    int voidOpenForDevice(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    long countByInstallIdHashAndOutcomeAndOutcomeAtAfter(String installIdHash, LoginOutcome outcome, LocalDateTime since);

    @Modifying
    @Query("delete from DeviceLoginTicket t where t.issuedAt < :cutoff")
    int deleteIssuedBefore(@Param("cutoff") LocalDateTime cutoff);
}
