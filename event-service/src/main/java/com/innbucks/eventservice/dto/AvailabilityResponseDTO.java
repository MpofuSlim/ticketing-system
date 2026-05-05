package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Tickets remaining for an event after a successful availability mutation.")
public class AvailabilityResponseDTO {

    @Schema(example = "7", description = "Tickets still available for purchase.")
    private int availableTickets;
}
