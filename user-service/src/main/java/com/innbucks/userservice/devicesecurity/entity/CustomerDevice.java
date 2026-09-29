package com.innbucks.userservice.devicesecurity.entity;

import com.innbucks.userservice.devicesecurity.DeviceState;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One (customer, device) pair in the DTX device registry. See V40 for why the
 * row is keyed by MSISDN and why the install id is only ever held hashed.
 */
@Entity
@Table(name = "customer_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The {@code deviceId} every endpoint exposes. Never the install id. */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "msisdn", nullable = false, length = 20, updatable = false)
    private String msisdn;

    @Column(name = "install_id_hash", nullable = false, length = 64, updatable = false)
    private String installIdHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private DeviceState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "prior_state", length = 16)
    private DeviceState priorState;

    @Column(name = "state_reason", length = 32)
    private String stateReason;

    @Column(name = "platform", length = 16)
    private String platform;

    @Column(name = "os_version", length = 32)
    private String osVersion;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "manufacturer", length = 64)
    private String manufacturer;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "last_seen_lat")
    private Double lastSeenLat;

    @Column(name = "last_seen_lng")
    private Double lastSeenLng;

    @Column(name = "last_seen_near", length = 64)
    private String lastSeenNear;

    @Column(name = "last_ip", length = 64)
    private String lastIp;

    @Column(name = "trusted_until")
    private LocalDateTime trustedUntil;

    @Column(name = "bound_at")
    private LocalDateTime boundAt;

    @Column(name = "cooling_until")
    private LocalDateTime coolingUntil;

    /**
     * Last time this phone verified a sign-in OTP for this number (V41). NULL means
     * it never has: any trust it holds came from watch mode and is provisional.
     */
    @Column(name = "otp_verified_at")
    private LocalDateTime otpVerifiedAt;

    @Column(name = "pin_grace_until")
    private LocalDateTime pinGraceUntil;

    @Column(name = "blocked_at")
    private LocalDateTime blockedAt;

    @Column(name = "blocked_until")
    private LocalDateTime blockedUntil;

    @Column(name = "ussd_unlockable", nullable = false)
    private boolean ussdUnlockable;

    @Column(name = "unlockable_after")
    private LocalDateTime unlockableAfter;

    @Column(name = "support_ref", length = 16)
    private String supportRef;

    @Column(name = "last_unlocked_at")
    private LocalDateTime lastUnlockedAt;

    @Column(name = "consecutive_dead_challenges", nullable = false)
    private int consecutiveDeadChallenges;

    @Column(name = "state_changed_at", nullable = false)
    private LocalDateTime stateChangedAt;

    @Column(name = "state_changed_by", length = 255)
    private String stateChangedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Moves the pair to {@code next}, remembering where it came from. The one
     * place a state changes, so {@code state_changed_at}/{@code _by} can never
     * disagree with the state itself.
     */
    public void transition(DeviceState next, String reason, String changedBy, LocalDateTime now) {
        if (state != next) {
            this.priorState = state;
        }
        this.state = next;
        this.stateReason = reason;
        this.stateChangedAt = now;
        this.stateChangedBy = changedBy;
        this.updatedAt = now;
    }

    /** Clears every block/ban field. Called whenever the pair leaves a stopped state. */
    public void clearStop() {
        this.blockedAt = null;
        this.blockedUntil = null;
        this.ussdUnlockable = false;
        this.unlockableAfter = null;
    }

    /** Whether the trust window is open at {@code now}. */
    public boolean trustedAt(LocalDateTime now) {
        return state == DeviceState.TRUSTED && trustedUntil != null && trustedUntil.isAfter(now);
    }

    /** Whether a PENDING_PIN device is still inside its PIN-retry grace at {@code now}. */
    public boolean inPinGrace(LocalDateTime now) {
        return state == DeviceState.PENDING_PIN && pinGraceUntil != null && pinGraceUntil.isAfter(now);
    }

    /**
     * Whether this phone has ever proved, with a sign-in OTP, that it holds the
     * number's SIM. Trust without that proof was granted by watch mode and must be
     * confirmed with one code once OTP is enforced.
     */
    public boolean possessionVerified() {
        return otpVerifiedAt != null;
    }

    /** Whether the new-device cooling period is running at {@code now} (§8.6). */
    public boolean coolingAt(LocalDateTime now) {
        return coolingUntil != null && coolingUntil.isAfter(now);
    }
}
