package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Otp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpRepository extends JpaRepository<Otp, Long> {

    Optional<Otp> findByPhoneNumber(String phoneNumber);

    @Modifying
    @Query("delete from Otp o where o.phoneNumber = :phone")
    int deleteByPhoneNumber(@Param("phone") String phoneNumber);

    @Modifying
    @Query("delete from Otp o where o.phoneNumber = :phone and o.code = :code and o.expiresAt > :now")
    int consume(@Param("phone") String phoneNumber,
                @Param("code") String code,
                @Param("now") Instant now);

    @Modifying
    @Query("delete from Otp o where o.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
