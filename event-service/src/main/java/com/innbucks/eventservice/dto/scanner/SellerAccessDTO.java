package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * booking-service's sale-time question (internal, V21): may this account sell
 * tickets for this event? When it may, as which agent, for which organizer,
 * and on what terms.
 */
@Schema(name = "SellerAccess")
public record SellerAccessDTO(
        @Schema(example = "true") boolean allowed,
        @Schema(example = "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e", nullable = true,
                description = "The team row (event_scanners.scanner_id).") UUID agentId,
        @Schema(example = "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b", nullable = true) UUID organizerUuid,
        @Schema(example = "Tendai Moyo", nullable = true) String displayName,
        @Schema(example = "200", nullable = true, description = "Null = no cap (and always null when denied).")
        Integer ticketCap,
        @Schema(example = "5.00", nullable = true) BigDecimal commissionPercent
) {
    public static SellerAccessDTO denied() {
        return new SellerAccessDTO(false, null, null, null, null, null);
    }
}
