package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false)
    private UUID seatId;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false)
    private String rowLabel;

    @Column(nullable = false)
    private Integer seatNumber;

    @Column(nullable = false)
    private String categoryName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtBooking;

    // e.g. 20260419-48291X — unique per seat
    @Column(nullable = false, unique = true)
    private String ticketNumber;

    // Denormalised "is this row still locking the seat?" — true while the
    // parent booking is PENDING/CONFIRMED, false when CANCELLED. Kept in
    // sync by a Postgres AFTER UPDATE trigger on bookings (see migration
    // V5) so application code can't forget. The partial unique index
    // `uq_active_booking_item_per_seat` enforces "at most one active
    // booking_item per seat_id" — closing the seat-pick race in
    // createBooking where two bookers' cross-checks could each see the
    // seat as free.
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = Boolean.TRUE;

    /**
     * When the ticket was scanned at the gate. Null means unredeemed —
     * single-shot per booking_item, enforced atomically by an UPDATE WHERE
     * {@code redeemed_at IS NULL}. A row whose first redeem landed is
     * forever excluded from the WHERE clause; a second scan touches 0 rows
     * and the service returns ALREADY_REDEEMED with the original
     * {@link #redeemedByName} + {@link #redeemedAt} so the rejection toast
     * still tells the gate-staff who scanned and when.
     */
    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    /** Stable cross-service identifier of the user who scanned the ticket
     *  (the team member's or organizer's {@code user_uuid}). Never updated
     *  after a successful redeem — the audit trail must not change. */
    @Column(name = "redeemed_by_user_uuid")
    private UUID redeemedByUserUuid;

    /**
     * Display name of the user who scanned the ticket, captured at redeem
     * time. Denormalised on purpose — if the team member is later
     * soft-disabled or renamed, the rejection-toast on a second scan still
     * shows the name they were known by ("already scanned by Tariro at
     * 19:42"). That contract must survive the user-service lifecycle of
     * the scanning row.
     */
    @Column(name = "redeemed_by_name")
    private String redeemedByName;
}
