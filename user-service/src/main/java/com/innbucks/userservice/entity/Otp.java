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

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(nullable = false)
    private Instant createdAt;
}
