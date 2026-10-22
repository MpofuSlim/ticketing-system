package com.innbucks.bookingservice.dto.promo;

import com.innbucks.bookingservice.entity.PromoCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A promo code's editable terms: the body of {@code PUT /bookings/promo-codes/{id}},
 * and the part of the create body after the event and the code. The whole set
 * is replaced on an edit, so send every field you want to keep.
 */
@Data
@Schema(name = "PromoCodeTerms")
public class PromoCodeTermsDTO {

    @Schema(example = "10% off early tickets", nullable = true)
    @Size(max = 200, message = "Description must be at most 200 characters")
    private String description;

    @Schema(example = "PERCENT", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "PERCENT: discountValue % off each eligible ticket (1-99). FIXED: discountValue off "
                    + "each eligible ticket.")
    @NotNull(message = "discountType is required")
    private PromoCode.DiscountType discountType;

    @Schema(example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "discountValue is required")
    @DecimalMin(value = "0.00", inclusive = false, message = "discountValue must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "discountValue can have at most 2 decimal places")
    private BigDecimal discountValue;

    @Schema(example = "100", nullable = true, description = "Bookings that may use the code; null = no limit.")
    @Positive(message = "maxUses must be at least 1")
    private Integer maxUses;

    @Schema(example = "1", nullable = true,
            description = "Bookings one buyer (phone, else email) may use it on; null = no limit.")
    @Positive(message = "maxUsesPerCustomer must be at least 1")
    private Integer maxUsesPerCustomer;

    @Schema(example = "2026-09-01T00:00:00", nullable = true,
            description = "When it can first be used: the organizer's local time with no zone, or an instant "
                    + "with Z or an offset. Null = now.")
    @Size(max = 40)
    private String startsAt;

    @Schema(example = "2026-09-08T23:59:59", nullable = true,
            description = "When it stops (exclusive), same form as startsAt. Null = no end.")
    @Size(max = 40)
    private String endsAt;

    @Schema(nullable = true, description = "The event's seat categories it discounts; empty or null = all of them.")
    @Size(max = 50, message = "At most 50 categories per promo code")
    private List<UUID> categoryIds;

    @Schema(example = "true", nullable = true, description = "False switches the code off; null = true.")
    private Boolean active;
}
