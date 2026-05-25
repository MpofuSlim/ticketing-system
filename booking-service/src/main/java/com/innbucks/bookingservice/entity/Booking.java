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

    // tenantId of the event being booked. Captured at booking creation by
    // calling event-service. Null when event-service was unreachable at the
    // time, in which case loyalty earn/redeem will be skipped at confirm.
    @Column
    private String tenantId;

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
