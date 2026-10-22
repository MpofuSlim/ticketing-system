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

/** A promo code as the organizer sees it, with how much it has been used. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PromoCode")
public class PromoCodeDTO {
    @Schema(example = "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e")
    private UUID id;
    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID eventId;
    @Schema(example = "EARLY10")
    private String code;
    @Schema(example = "10% off early tickets", nullable = true)
    private String description;
    @Schema(example = "PERCENT")
    private String discountType;
    @Schema(example = "10.00")
    private BigDecimal discountValue;
    @Schema(example = "100", nullable = true)
    private Integer maxUses;
    @Schema(example = "1", nullable = true)
    private Integer maxUsesPerCustomer;
    @Schema(example = "2026-09-01T00:00:00+02:00", nullable = true)
    private LocalDateTime startsAt;
    @Schema(example = "2026-09-08T23:59:59+02:00", nullable = true)
    private LocalDateTime endsAt;
    @Schema(description = "Seat categories it discounts; empty = all of them.")
    private List<UUID> categoryIds;
    @Schema(example = "true")
    private boolean active;
    @Schema(example = "37", description = "Bookings using it that still count (not cancelled or expired).")
    private long uses;
    @Schema(example = "555.00", description = "Total discount on those bookings.")
    private BigDecimal discountGiven;
    @Schema(example = "2026-08-25T08:00:00+02:00")
    private LocalDateTime createdAt;
}
