package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /bookings/numbering/{categoryId}/assign-sold}: how many were numbered, and the series after. */
@Schema(description = "Numbers given to paid tickets that had none")
public record AssignSoldResultDTO(
        @Schema(description = "Tickets numbered by this call (0 when none was left)", example = "37") int numberedNow,
        NumberingDTO numbering) {
}
