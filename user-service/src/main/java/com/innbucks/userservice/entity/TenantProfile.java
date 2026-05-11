package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "tenant_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private com.innbucks.userservice.entity.User user;

    private String businessName;
    private String businessAddress;
    private String businessEmail;
    private String businessPhoneNumber;
    private String registrationNumber;

    private String metaDataFilePath;

    // Loyalty merchant this MERCHANT_ADMIN administers. Populated once the
    // tenant onboards a merchant in loyalty-service; flows into the JWT
    // so loyalty endpoints can scope writes without trusting the request body.
    @Column(name = "loyalty_merchant_id")
    private UUID loyaltyMerchantId;

    private int totalEvents = 0;
    private double rating = 0.0;
}
