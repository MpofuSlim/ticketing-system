package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The answer to {@code POST /bookings/ticket-check}: a yes or no, and nothing about the tickets. */
@Schema(name = "TicketCheckResponse")
public record TicketCheckResponseDTO(
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID eventId,
        @Schema(example = "true", description = "True when the phone holds at least one CONFIRMED ticket "
                + "for the event: as the purchaser, or as a named guest with their own phone.")
        boolean hasTicket) {
}
