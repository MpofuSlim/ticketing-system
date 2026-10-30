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
                    + "before that are yyyyMMdd-DDDDDL (e.g. 20260619-48291X) and stay valid. A scanned "
                    + "value is sent as read. A TYPED value may drop the dashes, add spaces or use lower "
                    + "case (202606197kq4m9xz2b): it is read as the issued number, with no letter swapped "
                    + "for another.")
    private String ticketNumber;
}
