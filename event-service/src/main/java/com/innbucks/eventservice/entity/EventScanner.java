package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A phone number on an organizer's event team (V14): the super-app gate
 * scanner, and since V21 also the sales agent. A row scans ({@link #canScan}),
 * sells ({@link #canSell}), or both; one invite covers both.
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

    /**
     * When the invite message went to the number (V20); null until the first
     * assignment while INVITED, and again after inviting a decliner back. Set
     * by {@code EventScannerRepository.claimInviteNotice}, so two assignments
     * at once send one message.
     */
    @Column(name = "invite_notified_at")
    private LocalDateTime inviteNotifiedAt;

    /**
     * Scans at the gate (V21). True on every row that predates sales agents.
     * When false, the row's scan assignments are kept but grant nothing and do
     * not count toward the number's no-overlap rule.
     */
    @Builder.Default
    @Column(name = "can_scan", nullable = false)
    private boolean canScan = true;

    /**
     * Sells tickets for the organizer (V21): a sales agent. When false, the
     * row's sales assignments are kept but grant nothing.
     */
    @Builder.Default
    @Column(name = "can_sell", nullable = false)
    private boolean canSell = false;

    /**
     * Where the organizer sent a Foundry portal invite (V21); null = none.
     * user-service owns the invite and the account; this is only a record of
     * where it went.
     */
    @Column(name = "portal_email", length = 254)
    private String portalEmail;

    @Column(name = "portal_invited_at")
    private LocalDateTime portalInvitedAt;

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

    /**
     * The invite message's roles (V21), from what the row can do now:
     * {@code SCAN}, {@code SELL}, or both, in that order.
     */
    public List<String> roles() {
        List<String> roles = new ArrayList<>(2);
        if (canScan) roles.add("SCAN");
        if (canSell) roles.add("SELL");
        return List.copyOf(roles);
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
