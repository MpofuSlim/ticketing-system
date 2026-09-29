package com.innbucks.userservice.devicesecurity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A ban on the DEVICE for every customer (SHARED_DEVICE, CONFIRMED_FRAUD — §8.5).
 * Active while {@code liftedAt} is null.
 */
@Entity
@Table(name = "device_wide_bans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceWideBan {

    @Id
    @Column(name = "install_id_hash", nullable = false, length = 64)
    private String installIdHash;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "support_ref", nullable = false, length = 16)
    private String supportRef;

    @Column(name = "banned_at", nullable = false)
    private LocalDateTime bannedAt;

    @Column(name = "banned_by", length = 255)
    private String bannedBy;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "lifted_at")
    private LocalDateTime liftedAt;

    @Column(name = "lifted_by", length = 255)
    private String liftedBy;

    public boolean active() {
        return liftedAt == null;
    }
}
