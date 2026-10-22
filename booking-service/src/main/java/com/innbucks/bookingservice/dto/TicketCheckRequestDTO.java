package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /bookings/ticket-check}. Both fields are strings and are
 * validated in the controller AFTER the key check, so a caller without the key
 * never learns which field was wrong.
 */
@Schema(name = "TicketCheckRequest",
        description = "Does this phone hold a CONFIRMED ticket for this event?")
public record TicketCheckRequestDTO(
        @Schema(example = "+263771234567",
                description = "The phone to check, in any common spelling (+263771234567, 0771234567). "
                        + "Sent in the body, never the URL, so it does not reach access logs.")
        String phoneNumber,
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", description = "The event, a UUID.")
        String eventId) {
}
