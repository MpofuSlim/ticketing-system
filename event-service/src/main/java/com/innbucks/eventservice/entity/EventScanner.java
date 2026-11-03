package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A phone number on an organizer's gate team: the super-app scanner (V14).
 *
 * <p>Not an account. The person behind the number signs in to the super app as
 * usual and accepts the invite there, which binds the row to the account that
 * accepted ({@link #accountUserUuid}). Only that account scans for it.
 *
 * <p>Status is derived from the three timestamps, never stored, so no update
 * can leave a status that disagrees with them.
 */
@Entity
@Table(name = "event_scanners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventScanner {

    public enum Status { INVITED, ACTIVE, DECLINED, DISABLED }

    @Id
    @Column(name = "scanner_id")
    private UUID scannerId;

    /** user-service {@code users.user_uuid} of the owning EVENT_ORGANIZER. */
    @Column(name = "organizer_uuid", nullable = false, updatable = false)
    private UUID organizerUuid;

    /** E.164, as {@code MsisdnValidator} canonicalises it. */
    @Column(name = "phone_number", nullable = false, updatable = false, length = 20)
    private String phoneNumber;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    /** The account that accepted; null until accepted, cleared on leaving. */
    @Column(name = "account_user_uuid")
    private UUID accountUserUuid;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public Status status() {
        if (disabledAt != null) return Status.DISABLED;
        if (declinedAt != null) return Status.DECLINED;
        if (acceptedAt != null) return Status.ACTIVE;
        return Status.INVITED;
    }

    /** The name shown in the scan log and in "already scanned by". */
    public String displayName() {
        return (firstName + " " + lastName).trim();
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
