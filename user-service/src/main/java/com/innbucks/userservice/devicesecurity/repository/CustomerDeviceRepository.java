package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.DeviceState;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerDeviceRepository extends JpaRepository<CustomerDevice, Long> {

    Optional<CustomerDevice> findByMsisdnAndInstallIdHash(String msisdn, String installIdHash);

    Optional<CustomerDevice> findByPublicId(UUID publicId);

    Optional<CustomerDevice> findByPublicIdAndMsisdn(UUID publicId, String msisdn);

    List<CustomerDevice> findByMsisdnOrderByLastSeenAtDesc(String msisdn);

    List<CustomerDevice> findByInstallIdHash(String installIdHash);

    long countByMsisdnAndState(String msisdn, DeviceState state);

    Page<CustomerDevice> findByStateInOrderByStateChangedAtDesc(Collection<DeviceState> states, Pageable pageable);

    /**
     * Creates the NEW row for a (customer, device) pair unless one exists.
     * {@code ON CONFLICT DO NOTHING} rather than find-then-save, so two
     * simultaneous first sign-ins from the same phone cannot race each other
     * into a unique-constraint 500.
     */
    @Modifying
    @Query(value = """
            INSERT INTO customer_devices (public_id, msisdn, install_id_hash, state, first_seen_at,
                last_seen_at, state_changed_at, state_changed_by, created_at, updated_at,
                ussd_unlockable, consecutive_dead_challenges, version)
            VALUES (:publicId, :msisdn, :installIdHash, 'NEW', :now, :now, :now, 'SYSTEM', :now, :now,
                FALSE, 0, 0)
            ON CONFLICT (msisdn, install_id_hash) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("publicId") UUID publicId,
                       @Param("msisdn") String msisdn,
                       @Param("installIdHash") String installIdHash,
                       @Param("now") LocalDateTime now);

    /**
     * Other customers this physical device has been bound to and not removed from —
     * the mule-farm count (§8.2, §8.5).
     */
    @Query("""
            select count(distinct d.msisdn) from CustomerDevice d
            where d.installIdHash = :installIdHash and d.msisdn <> :msisdn
              and d.boundAt is not null and d.state <> com.innbucks.userservice.devicesecurity.DeviceState.REVOKED
            """)
    long countOtherBoundCustomers(@Param("installIdHash") String installIdHash, @Param("msisdn") String msisdn);

    /** Retention (§12): device rows unseen for the retention window go, except bans, which are security records. */
    @Modifying
    @Query("""
            delete from CustomerDevice d
            where d.lastSeenAt < :cutoff and d.state <> com.innbucks.userservice.devicesecurity.DeviceState.BANNED
            """)
    int deleteUnseenSince(@Param("cutoff") LocalDateTime cutoff);
}
