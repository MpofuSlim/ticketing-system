package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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
    private String idNumber;
    private String passportNumber;
    private String address;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String selfiePicturePath;

    private String biometricsReference;

    private String idDocumentPath;
    private String proofOfResidencePath;
    private String passportDocumentPath;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }
}
