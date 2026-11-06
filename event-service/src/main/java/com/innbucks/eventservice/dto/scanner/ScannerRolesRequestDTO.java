package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** What a team member does (V21): scan, sell, or both. Both are required; at least one must be true. */
@Schema(name = "ScannerRolesRequest")
public record ScannerRolesRequestDTO(
        @Schema(example = "true", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Scans tickets at the gate.")
        @NotNull(message = "canScan is required")
        Boolean canScan,

        @Schema(example = "true", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Sells tickets for you (a sales agent).")
        @NotNull(message = "canSell is required")
        Boolean canSell
) { }
