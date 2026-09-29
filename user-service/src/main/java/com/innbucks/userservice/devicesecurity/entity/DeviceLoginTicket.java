package com.innbucks.userservice.devicesecurity.entity;

import com.innbucks.userservice.devicesecurity.LoginOutcome;
import com.innbucks.userservice.devicesecurity.SignInContext;
import com.innbucks.userservice.devicesecurity.SignInPurpose;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Server-side record of one login ticket. The signed JWT itself is never
 * stored; this row is what makes it single-use and what the broker's login
 * result is matched against.
 */
@Entity
@Table(name = "device_login_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceLoginTicket {

    @Id
    @Column(name = "jti", nullable = false, length = 40)
    private String jti;

    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    @Column(name = "customer_device_id")
    private Long customerDeviceId;

    @Column(name = "install_id_hash", nullable = false, length = 64)
    private String installIdHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    private SignInPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "context", nullable = false, length = 16)
    private SignInContext context;

    @Column(name = "otp_verified", nullable = false)
    private boolean otpVerified;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private LoginOutcome outcome;

    @Column(name = "outcome_at")
    private LocalDateTime outcomeAt;

    @Column(name = "staging_code", length = 32)
    private String stagingCode;
}
