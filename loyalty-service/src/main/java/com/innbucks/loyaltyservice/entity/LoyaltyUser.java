package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loyalty_users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_tenant_phone", columnNames = {"tenant_id", "phone"})
}, indexes = {
        @Index(name = "idx_user_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Column(name = "national_id", length = 64)
    private String nationalId;

    @Column(length = 8)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role = Role.END_USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Role { END_USER, MERCHANT_ADMIN, MERCHANT_FINANCE, TENANT_ADMIN, PLATFORM_ADMIN, AUDITOR }
    public enum Status { ACTIVE, BLOCKED, INACTIVE }
}
