package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A category's early-bird offer as read back. Present whenever one is set,
 * including after it has ended ({@code active: false}), so the console can show
 * what ran; null when the category never had one or it was removed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EarlyBird")
public class EarlyBirdDTO {

    @Schema(example = "100.00", description = "Early-bird ticket price.")
    private BigDecimal price;

    @Schema(example = "2026-09-08T23:59:59+02:00",
            description = "When the early-bird price stops applying, at the market's offset.")
    private LocalDateTime endsAt;

    @Schema(example = "true", description = "True while the early-bird price is the one charged.")
    private boolean active;
}
