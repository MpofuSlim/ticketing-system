package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.ChallengeStatus;
import com.innbucks.userservice.devicesecurity.SignInPurpose;
import com.innbucks.userservice.devicesecurity.entity.DeviceOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeviceOtpChallengeRepository extends JpaRepository<DeviceOtpChallenge, String> {

    Optional<DeviceOtpChallenge> findFirstByCustomerDeviceIdAndPurposeAndStatusOrderByCreatedAtDesc(
            Long customerDeviceId, SignInPurpose purpose, ChallengeStatus status);

    List<DeviceOtpChallenge> findByMsisdnAndStatus(String msisdn, ChallengeStatus status);

    long countByMsisdnAndCreatedAtAfter(String msisdn, LocalDateTime since);

    long countByInstallIdHashAndCreatedAtAfter(String installIdHash, LocalDateTime since);

    long countByRequestIpAndCreatedAtAfter(String requestIp, LocalDateTime since);

    /** Kills every open challenge on a device — a block, ban or removal must not leave a live code behind. */
    @Modifying
    @Query("""
            update DeviceOtpChallenge c set c.status = com.innbucks.userservice.devicesecurity.ChallengeStatus.VOID,
                c.closedAt = :now, c.version = c.version + 1
            where c.customerDeviceId = :deviceId and c.status = com.innbucks.userservice.devicesecurity.ChallengeStatus.OPEN
            """)
    int voidOpenForDevice(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from DeviceOtpChallenge c where c.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
