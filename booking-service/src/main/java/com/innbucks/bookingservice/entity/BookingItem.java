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

    /**
     * OPTIONAL named attendee for THIS ticket — who will actually present it
     * at the gate when the buyer is bringing others. All three null = the
     * ticket is the purchaser's own (the common case; a one-ticket booking
     * never needs these). When a phone and/or email is given, this ticket's
     * QR / confirmation is ALSO delivered to that person directly, so each
     * guest arrives with their own scannable ticket on their own phone
     * rather than depending on the buyer forwarding a screenshot.
     *
     * <p>{@code attendeePhone} is stored in E.164 — the controller validates
     * and canonicalises it exactly as it does the purchaser's number, so the
     * delivery path never hands the WhatsApp gateway a malformed MSISDN.
     * (V22)
     */
    @Column(name = "attendee_name")
    private String attendeeName;

    @Column(name = "attendee_email")
    private String attendeeEmail;

    @Column(name = "attendee_phone")
    private String attendeePhone;

    /**
     * The person this ticket is for, as the gate/ticket should show it: the
     * named attendee when there is one, otherwise the purchaser. Null only for
     * pre-V22 rows whose booking carries no name.
     */
    public String holderName() {
        if (attendeeName != null && !attendeeName.isBlank()) {
            return attendeeName;
        }
        return booking == null ? null : booking.getCustomerName();
    }

    /** True when at least one attendee contact (phone or email) is present. */
    public boolean hasAttendeeContact() {
        return (attendeePhone != null && !attendeePhone.isBlank())
                || (attendeeEmail != null && !attendeeEmail.isBlank());
    }

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
