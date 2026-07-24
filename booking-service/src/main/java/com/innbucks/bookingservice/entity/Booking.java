package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Optimistic-lock token. Hibernate increments it on every UPDATE and
    // adds `... WHERE id = ? AND version = ?` to the generated SQL, so two
    // concurrent confirms can't both commit. The second one's UPDATE
    // touches 0 rows and throws OptimisticLockException — confirmBooking
    // surfaces that as a 409 Conflict ("booking already confirmed").
    @Version
    @Column(nullable = false)
    private Long version;

    @Column
    private String userEmail;

    // Captured from the JWT's phoneNumber claim at booking time. Optional —
    // some JWTs (system users, older tokens) don't carry the claim, so the
    // booking is still valid without one. Indexed so `findByPhoneNumber*`
    // lookups don't full-scan.
    @Column
    private String phoneNumber;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, unique = true)
    private String confirmationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // Stable cross-service identifier of the owning organizer, mirrored
    // from events.tenant_user_uuid via EventLookupDTO at create time.
    // Powers the team-member scan authorization: the scanner's
    // organizerUuid JWT claim must equal this column for the ticket to
    // redeem. Null only when event-service was unreachable at create —
    // such a ticket refuses with WRONG_ORGANIZER at the gate.
    @Column(name = "tenant_user_uuid")
    private UUID tenantUserUuid;

    // Filled in at /confirm when the customer redeems points. Null on
    // pending or pure-cash confirmations.
    @Column(precision = 18, scale = 4)
    private BigDecimal pointsUsed;

    // The cash portion paid at /confirm. Defaults to totalAmount on a
    // pure-cash confirmation; reduced when points were used.
    @Column(precision = 10, scale = 2)
    private BigDecimal cashAmount;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<BookingItem> items;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // When a PENDING booking's seat-hold runs out. The expiration scheduler
    // flips such bookings to CANCELLED and releases the seats. Null for
    // CONFIRMED (paid — no hold) and for legacy bookings created before the
    // hold model existed.
    private LocalDateTime expiresAt;

    // When the DAY-OF pre-event reminder was sent (or consumed) for this
    // CONFIRMED booking. Set on the reminder attempt — success or failure —
    // so the hourly EventReminderScheduler never retries into spam; also set
    // silently for bookings whose event already started. Null = not yet
    // considered. UTC, like every LocalDateTime here.
    private LocalDateTime reminderSentAt;

    // When the T-2-DAYS reminder stage (SMS + email) was sent or consumed —
    // same exactly-once semantics as reminderSentAt. Stamped silently when
    // the booking only entered the scan inside the day-of window, so a
    // late booker gets one reminder, not two back-to-back. (V19)
    @Column(name = "reminder2d_sent_at")
    private LocalDateTime reminder2dSentAt;

    // Idempotency guard for the event-service availability release. Set true
    // after a successful PATCH /events/{id}/availability/release as part of
    // reversing a CONFIRMED booking; the reverse handler short-circuits the
    // release call when this is already true so a retry never double-credits
    // the event's stored availableTickets.
    @Column(name = "availability_released", nullable = false)
    @Builder.Default
    private boolean availabilityReleased = false;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = BookingStatus.PENDING;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public enum BookingStatus {
        PENDING,
        CONFIRMED,
        CANCELLED
    }
}
