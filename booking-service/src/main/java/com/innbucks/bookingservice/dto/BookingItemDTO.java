package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingItemDTO {
    /**
     * This ticket's own id, on the authenticated views only: the key for the
     * attendee edit by item. Never on the public magic-link or phone views.
     */
    @Schema(example = "f1c0d2e3-2345-6789-abcd-ef0123456789", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID bookingItemId;
    private UUID seatId;
    private UUID categoryId;
    private String categoryName;
    private String rowLabel;
    private Integer seatNumber;
    private BigDecimal priceAtBooking;

    /** The price tier the ticket was sold at (e.g. "Early bird"); absent at the full price. */
    @Schema(example = "Early bird", nullable = true,
            description = "Price tier this ticket was sold at; absent when it was sold at the full price.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String priceTierName;

    /** The promo code's discount on this ticket (V27); priceAtBooking is after it. Absent when none. */
    @Schema(example = "10.00", nullable = true,
            description = "Promo code discount on this ticket; priceAtBooking is what was paid after it. "
                    + "Absent when no code applied to the ticket.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal discountAmount;

    // e.g. 20260419-7KQ4M-9XZ2B (legacy tickets: 20260419-48291X). Masked
    // (20260419-*****-**Z2B) whenever someone other than the buyer reads the
    // booking.
    private String ticketNumber;

    // Per-seat scan code. Base64 PNG wrapped as a data URI so the frontend
    // can render with `<img src={qrCode} />`. Encodes the ticketNumber. Null
    // wherever the ticketNumber is masked: it IS the credential.
    private String qrCode;

    /**
     * The named attendee for this ticket, when the purchaser gave one (V22).
     * Null = the purchaser's own ticket. Present on EVERY view of an item —
     * including the public magic-link / phone-wallet ones — because the name
     * is printed on the ticket face; it is what tells the buyer which QR to
     * hand to whom.
     */
    @Schema(example = "Tendai Ncube", nullable = true,
            description = "Named attendee for this ticket; null when it is the purchaser's own.")
    private String attendeeName;

    /**
     * Attendee contact — populated ONLY on the authenticated booking views
     * ({@code POST /bookings}, {@code GET /bookings/{id}}, {@code /my},
     * {@code /phone/{n}}, {@code /confirmation/{n}}). The public, unauthenticated
     * views ({@code /public/{id}}, {@code /public/phone/{n}}) never set them,
     * and NON_NULL keeps the key out of those payloads entirely rather than
     * advertising a field that is always null there. Same PII posture as the
     * purchaser's own email/phone on those DTOs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(example = "tendai@example.com", nullable = true,
            description = "Attendee email. Authenticated views only; absent from public views.")
    private String attendeeEmail;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(example = "+263772000000", nullable = true,
            description = "Attendee phone (E.164). Authenticated views only; absent from public views.")
    private String attendeePhone;

    /**
     * This ticket's answers to the organizer's booking questions (V32), on the
     * buyer's own reads and the organizer's and staff's: never on a public
     * view, the S2S read or a confirm/cancel answer, where the key is absent.
     * [] when none, or once deleted (90 days after the event ends).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(nullable = true, description = "This ticket's answers. Authenticated reads only; absent elsewhere.")
    private java.util.List<BookingAnswerDTO> answers;

    /** Add-ons bought with this ticket (V33), name and price as bought; [] when none. On every view. */
    @Schema(description = "Add-ons bought with this ticket; [] when none.")
    private java.util.List<BookingAddOnDTO> addOns;

    @io.swagger.v3.oas.annotations.media.Schema(description = "The ticket's assigned number (bib or seat), given when it is paid for. Absent when it has none.", example = "T014")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String assignedNumber;

    @io.swagger.v3.oas.annotations.media.Schema(description = "What the number is called, e.g. Bib, Seat, Table. Absent when the ticket has no number.", example = "Bib")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String numberLabel;
}
