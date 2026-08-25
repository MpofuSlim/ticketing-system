package com.innbucks.userservice.entity;

import com.innbucks.userservice.entity.converter.JsonStringMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "customer_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    @Builder.Default
    private int registrationTier = 1;

    private String fullName;

    @Column(name = "national_id")
    private String nationalId;

    @Embedded
    private CustomerProfileAddress address;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Convert(converter = JsonStringMapConverter.class)
    @Column(name = "client_custom_fields", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, String> clientCustomFields = new LinkedHashMap<>();

    private String biometricsReference;

    private String idDocumentPath;
    private String proofOfResidencePath;
    private String passportDocumentPath;

    // The core-banking linkage columns (oradian_external_id, oradian_client_id
    // from V10; core_banking_provider, core_banking_profile_id from V19) are no
    // longer mapped. Tier-2 registration used to mirror the customer into
    // Oradian and stamp its references here; Oradian is gone and nothing
    // server-side replaced it, so the columns have no writer.
    //
    // They are left in place rather than dropped — same call as the dormant
    // event_outbox table after Kafka was removed. Applied migrations are never
    // edited, unmapped columns are harmless under ddl-auto: validate, and the
    // existing rows are real history: they still name the Oradian record each
    // pre-cutover tier-2 customer was mirrored into, which is exactly what a
    // reconciliation or support query would need. Drop them in a later
    // migration once that history is genuinely worthless.

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    // A01/A04: the LAST time this phone completed OTP verification. Stamped on
    // every successful /auth/otp/verify (OtpService.finalizeVerification) so the
    // tier2/3/4 KYC-upgrade endpoints can require a RECENT verification (proof
    // the caller owns the phone) instead of trusting the request-body msisdn.
    // Nullable: legacy rows and phones that never OTP-verified have no stamp.
    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }
}
