package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * One single-use TOTP-recovery code, persisted as a bcrypt hash. The plaintext
 * is shown to the user exactly once (right after they finish enrolment) and
 * never stored anywhere. Consumed atomically with
 * {@code UPDATE mfa_backup_codes SET used_at = ? WHERE id = ? AND used_at IS NULL}
 * so a code wins at most one race.
 */
@Entity
@Table(name = "mfa_backup_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaBackupCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 255 (was 72, sized for BCrypt) so Argon2id encoded hashes (~100 chars incl.
    // the "{argon2}" prefix) fit — see migration V27. Matches the widened column
    // so ddl-auto: validate passes.
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    /** Set the moment a code is consumed; NULL while unused. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
