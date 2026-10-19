package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CustomerSecurityProfileRepository extends JpaRepository<CustomerSecurityProfile, String> {

    /** Race-free get-or-create, same reasoning as {@link CustomerDeviceRepository#insertIfAbsent}. */
    @Modifying
    @Query(value = """
            INSERT INTO customer_security_profiles (msisdn, created_at, updated_at, version)
            VALUES (:msisdn, :now, :now, 0)
            ON CONFLICT (msisdn) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("msisdn") String msisdn, @Param("now") LocalDateTime now);

    /**
     * Bounds every lock wait for the rest of the current transaction ({@code SET LOCAL}),
     * e.g. {@code "2000ms"}. A wait that runs out is SQLSTATE 55P03, which reaches the
     * caller as a {@code ConcurrencyFailureException}.
     */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLockTimeout(@Param("timeout") String timeout);

    /** The number's row, locked for this transaction: the per-number mutex ({@code NumberLock}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CustomerSecurityProfile p where p.msisdn = :msisdn")
    Optional<CustomerSecurityProfile> lockByMsisdn(@Param("msisdn") String msisdn);
}
