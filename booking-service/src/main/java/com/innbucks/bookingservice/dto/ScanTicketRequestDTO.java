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
    @Schema(example = "20260619-48291X",
            description = "Per-seat ticket identifier, encoded in the QR. Unique across all bookings.")
    private String ticketNumber;
}
