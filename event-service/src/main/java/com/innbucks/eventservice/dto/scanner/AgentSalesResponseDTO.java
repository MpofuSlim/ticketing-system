package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Empty is the normal answer for anyone who is not a sales agent: the app then hides its "Sell tickets" tool. */
@Schema(name = "AgentSales")
public record AgentSalesResponseDTO(
        @Schema(description = "Events of accepted, switched-on selling teams that have not ended, soonest first.")
        List<AgentSalesEventDTO> events
) { }
