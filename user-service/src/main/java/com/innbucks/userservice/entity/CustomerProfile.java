package com.innbucks.userservice.entity;

import com.innbucks.userservice.entity.converter.JsonStringMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }
}
