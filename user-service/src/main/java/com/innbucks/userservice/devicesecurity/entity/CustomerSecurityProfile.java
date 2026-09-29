package com.innbucks.userservice.devicesecurity.entity;

import com.innbucks.userservice.devicesecurity.OtpChannel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Per-customer facts that are not about one device (preferred channel, fraud flag, PIN issue). */
@Entity
@Table(name = "customer_security_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSecurityProfile {

    @Id
    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_channel", length = 16)
    private OtpChannel preferredChannel;

    @Column(name = "fraud_flagged_at")
    private LocalDateTime fraudFlaggedAt;

    @Column(name = "fraud_flag_note", length = 1000)
    private String fraudFlagNote;

    @Column(name = "pin_issued_at")
    private LocalDateTime pinIssuedAt;

    @Column(name = "last_sign_in_at")
    private LocalDateTime lastSignInAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean fraudFlagged() {
        return fraudFlaggedAt != null;
    }
}
