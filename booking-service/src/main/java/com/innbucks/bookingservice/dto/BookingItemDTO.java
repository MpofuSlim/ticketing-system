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
    private UUID seatId;
    private UUID categoryId;
    private String categoryName;
    private String rowLabel;
    private Integer seatNumber;
    private BigDecimal priceAtBooking;
    private String ticketNumber; // e.g. 20260419-48291X

    // Per-seat scan code. Base64 PNG wrapped as a data URI so the frontend
    // can render with `<img src={qrCode} />`. Encodes the ticketNumber.
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
}
