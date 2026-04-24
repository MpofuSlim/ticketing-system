package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "otp_retry_attempts",
        indexes = @Index(name = "idx_otp_retry_phone", columnList = "phone_number", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpRetryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    private Instant windowStartsAt;

    private Instant lockedUntil;
}
