package com.innbucks.userservice.devicesecurity.entity;

import com.innbucks.userservice.devicesecurity.ChallengeStatus;
import com.innbucks.userservice.devicesecurity.OtpChannel;
import com.innbucks.userservice.devicesecurity.SignInPurpose;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One OTP challenge. Created when DTX decides OTP_REQUIRED; a code is minted and
 * sent only when the app picks a channel ({@code /otp/send}), so a customer who
 * walks away costs nothing. {@code codeHash} is the HMAC of the current code —
 * the code itself is never stored.
 */
@Entity
@Table(name = "device_otp_challenges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceOtpChallenge {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    @Column(name = "customer_device_id", nullable = false)
    private Long customerDeviceId;

    @Column(name = "install_id_hash", nullable = false, length = 64)
    private String installIdHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private SignInPurpose purpose;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChallengeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16)
    private OtpChannel channel;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "attempts_left", nullable = false)
    private int attemptsLeft;

    @Column(name = "resends_left", nullable = false)
    private int resendsLeft;

    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "session_action", length = 32)
    private String sessionAction;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "resend_after")
    private LocalDateTime resendAfter;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Open and not yet expired at {@code now}. */
    public boolean liveAt(LocalDateTime now) {
        return status == ChallengeStatus.OPEN && expiresAt.isAfter(now);
    }

    public void close(ChallengeStatus terminal, LocalDateTime now) {
        this.status = terminal;
        this.closedAt = now;
    }
}
