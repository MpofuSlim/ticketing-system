package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CustomerSecurityProfileRepository extends JpaRepository<CustomerSecurityProfile, String> {

    /** Race-free get-or-create, same reasoning as {@link CustomerDeviceRepository#insertIfAbsent}. */
    @Modifying
    @Query(value = """
            INSERT INTO customer_security_profiles (msisdn, created_at, updated_at, version)
            VALUES (:msisdn, :now, :now, 0)
            ON CONFLICT (msisdn) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("msisdn") String msisdn, @Param("now") LocalDateTime now);
}
