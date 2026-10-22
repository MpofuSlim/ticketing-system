package com.innbucks.bookingservice.dto.promo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a valid code gives, for the checkout to show before the booking. Not a
 * reservation: the booking checks the code again, under its lock, and can
 * still refuse it (used up meanwhile, or the buyer's own limit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PromoCodeCheck")
public class PromoCodeCheckDTO {
    @Schema(example = "EARLY10")
    private String code;
    @Schema(example = "10% off early tickets", nullable = true)
    private String description;
    @Schema(example = "PERCENT")
    private String discountType;
    @Schema(example = "10.00")
    private BigDecimal discountValue;
    @Schema(description = "Seat categories it discounts; empty = all of them.")
    private List<UUID> categoryIds;
    @Schema(example = "2026-09-08T23:59:59+02:00", nullable = true)
    private LocalDateTime endsAt;
}
