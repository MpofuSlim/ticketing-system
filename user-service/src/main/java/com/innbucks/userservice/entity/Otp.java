package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "otps", indexes = @Index(name = "idx_otps_phone", columnList = "phone_number", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Otp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    // Stores the HMAC-SHA256 (hex) of the code, not the code itself (A02, V30) —
    // 64 chars. OtpHasher seals on write; verification hashes the submitted code
    // and matches HMAC-to-HMAC.
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(nullable = false)
    private Instant createdAt;
}
