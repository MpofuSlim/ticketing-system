package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    Optional<PendingRegistration> findByPhoneNumber(String phoneNumber);

    @Modifying
    @Query("delete from PendingRegistration p where p.phoneNumber = :phone")
    int deleteByPhoneNumber(@Param("phone") String phoneNumber);

    @Modifying
    @Query("delete from PendingRegistration p where p.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
