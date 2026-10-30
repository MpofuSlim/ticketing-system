package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "ScanTicketRequest",
        description = "Body for an EVENT_ORGANIZER or TEAM_MEMBER to redeem a ticket at the gate. " +
                      "The ticketNumber is the value embedded in the QR — the scanner app reads it " +
                      "off the customer's phone and POSTs it here. The scanner's identity (and " +
                      "their owning organizer) comes from the JWT; the body carries only the ticket.")
public class ScanTicketRequestDTO {

    @NotBlank(message = "ticketNumber is required")
    @Schema(example = "20260619-7KQ4M-9XZ2B",
            description = "Per-seat ticket identifier, encoded in the QR. Unique across all bookings. "
                    + "Issued as yyyyMMdd-XXXXX-XXXXX (upper-case Crockford base32); tickets issued "
                    + "before that are yyyyMMdd-DDDDDL (e.g. 20260619-48291X) and stay valid. Send the "
                    + "value exactly as scanned: it is matched as issued.")
    private String ticketNumber;

    @Schema(example = "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0", nullable = true,
            description = "The event the scanner is scanning for. REQUIRED from a super-app scanner (a "
                    + "CUSTOMER session); optional for the scanner app. When sent, a ticket for any other "
                    + "event is WRONG_EVENT and is not redeemed.")
    private java.util.UUID eventId;
}
