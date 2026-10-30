package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "GateInviteDecision")
public record GateInviteDecisionDTO(
        @Schema(example = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d") UUID scannerId,
        @Schema(example = "ACTIVE", allowableValues = {"ACTIVE", "DECLINED"}) String status,
        @Schema(example = "Harare Arena Events", nullable = true) String organizerName
) { }
