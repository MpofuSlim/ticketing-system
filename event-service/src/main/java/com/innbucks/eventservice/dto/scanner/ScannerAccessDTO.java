package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * booking-service's scan-time question (internal): may this account scan this
 * event? When it may, who for and under what name.
 */
@Schema(name = "ScannerAccess")
public record ScannerAccessDTO(
        @Schema(example = "true") boolean allowed,
        @Schema(example = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e", nullable = true) UUID scannerId,
        @Schema(example = "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b", nullable = true) UUID organizerUuid,
        @Schema(example = "Tendai Moyo", nullable = true) String displayName
) {
    public static ScannerAccessDTO denied() {
        return new ScannerAccessDTO(false, null, null, null);
    }
}
