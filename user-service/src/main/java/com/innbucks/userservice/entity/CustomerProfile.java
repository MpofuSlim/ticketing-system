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

    // Oradian linkage stamped at tier-2 by CustomerService.registerTier2 after
    // POST /internal/customers on the Oradian middleware succeeds. Lets us
    // reconcile the local customer with Oradian's Person+Client without
    // re-querying Oradian on every read. Both nullable: tier-1 customers and
    // legacy rows have no Oradian record yet. Uniqueness is enforced by
    // partial unique indexes (V10 migration) rather than @Column(unique=true)
    // so multiple NULLs are allowed for tier-1 customers.
    @Column(name = "oradian_external_id", length = 64)
    private String oradianExternalId;

    @Column(name = "oradian_client_id")
    private Long oradianClientId;

    // Provider-agnostic core-banking linkage (V19). The provider tag is the
    // CoreBankingPort.provider() of the cell that created the record
    // ("ORADIAN" / "VEENGU"); profileId is that provider's stable customer
    // reference (Oradian externalID / Veengu individual id). Written
    // alongside the oradian_* columns above — those stay in lockstep on
    // Oradian cells for existing tooling, and stay null on Veengu cells.
    @Column(name = "core_banking_provider", length = 16)
    private String coreBankingProvider;

    @Column(name = "core_banking_profile_id", length = 64)
    private String coreBankingProfileId;

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
