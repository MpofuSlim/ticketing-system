package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Both empty is the normal answer: the app then hides its "Scan tickets" tool. */
@Schema(name = "Gate")
public record GateResponseDTO(
        @Schema(description = "Accepted teams' events that have not ended, soonest first.") List<GateEventDTO> events,
        @Schema(description = "Teams waiting for this person to accept.") List<GateInviteDTO> invites
) { }
