package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A seat category's early-bird offer on the event page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventEarlyBird")
public class EventEarlyBirdDTO {

    @Schema(example = "100.00", description = "Early-bird ticket price.")
    private BigDecimal price;

    @Schema(example = "2026-09-08T23:59:59+02:00",
            description = "When the early-bird price stops applying, at the market's offset.")
    private LocalDateTime endsAt;

    @Schema(example = "true", description = "True while the early-bird price is the one charged.")
    private boolean active;
}
