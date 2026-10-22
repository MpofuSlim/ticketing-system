package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One entry of a category's price history ({@code GET /seat-categories/{id}/price-history}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PriceChange")
public class PriceChangeDTO {

    @Schema(example = "4b6d8f0a-1c2e-4a5b-9c7d-0e1f2a3b4c5d")
    private UUID id;

    @Schema(example = "TIERS_CHANGED",
            description = "CREATED: the category was created. PRICE_CHANGED: the full price moved. "
                    + "TIERS_CHANGED: the price tiers were replaced.")
    private String changeType;

    @Schema(example = "150.00", nullable = true, description = "The full price before; null on CREATED and TIERS_CHANGED.")
    private BigDecimal oldPrice;

    @Schema(example = "160.00", nullable = true,
            description = "The full price after (on CREATED, the price it was created at); null on TIERS_CHANGED.")
    private BigDecimal newPrice;

    @Schema(nullable = true, description = "The tiers before, as they were; null on CREATED and PRICE_CHANGED.")
    private List<Tier> tiersBefore;

    @Schema(nullable = true, description = "The tiers after (on CREATED, the ones it was created with); "
            + "null on PRICE_CHANGED.")
    private List<Tier> tiersAfter;

    @Schema(example = "7a1c3e5f-2b4d-4f6a-8c0e-1a2b3c4d5e6f", nullable = true,
            description = "Who made the change: the organizer's uuid, or the admin's email.")
    private String changedBy;

    @Schema(example = "2026-09-02T10:15:00+02:00", description = "When, at the market's offset.")
    private LocalDateTime changedAt;

    /** One tier's terms as they were at the change. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "PriceChangeTier")
    public static class Tier {
        @Schema(example = "Early bird")
        private String name;
        @Schema(example = "100.00")
        private BigDecimal price;
        @Schema(example = "2026-09-01T00:00:00+02:00", nullable = true)
        private LocalDateTime startsAt;
        @Schema(example = "2026-09-08T23:59:59+02:00", nullable = true)
        private LocalDateTime endsAt;
        @Schema(example = "100", nullable = true)
        private Integer quantity;
        @Schema(example = "4", nullable = true)
        private Integer maxPerCustomer;
        @Schema(example = "true")
        private boolean refundsReopen;
    }
}
