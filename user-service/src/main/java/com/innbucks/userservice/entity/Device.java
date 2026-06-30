package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(name = "uk_devices_user_device", columnNames = {"user_id", "device_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    private String deviceName;

    private String platform;

    private String pushToken;

    @Column(updatable = false)
    private LocalDateTime registeredAt = LocalDateTime.now(ZoneOffset.UTC);

    /**
     * SHA-256 (hex) hash of the "remember this device" trust token handed to the
     * client once at step-2 of an MFA login. NULL when no trust is established
     * (the common case). We store only the hash — never the raw token — mirroring
     * {@code refresh_tokens.token_hash}; a DB leak can't be replayed as a live
     * 2FA bypass. Compared on step-1 with a constant-time {@code MessageDigest.isEqual}.
     */
    @Column(name = "mfa_trust_token_hash")
    private String mfaTrustTokenHash;

    /**
     * UTC instant until which this device's trust token skips the step-1 MFA
     * challenge. Trust is honoured only while this is in the future. NULL when no
     * trust is established. Cleared (alongside the hash) on password change and
     * MFA disable/reset.
     */
    @Column(name = "mfa_trusted_until")
    private LocalDateTime mfaTrustedUntil;
}
