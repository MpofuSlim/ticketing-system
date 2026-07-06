package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Editable metadata for an existing seat category: {@code name}, {@code description},
 * {@code price}. Deliberately does NOT carry {@code eventId} or {@code sections} —
 *
 * <ul>
 *   <li>{@code eventId} is immutable: a category can't be moved to another event
 *       (its seats + any bookings are anchored to the original).</li>
 *   <li>seat layout ({@code sections} / total seats) is immutable here because the
 *       seats are already materialised as rows and may be PENDING/CONFIRMED in
 *       booking-service — resizing them safely is a separate, much larger feature
 *       (add/remove individual seats with a booking-collision check), not a
 *       metadata edit.</li>
 * </ul>
 */
@Data
@Schema(name = "UpdateCategoryRequest",
        description = "Mutable metadata of a seat category. Seat layout and event are immutable here.")
public class UpdateCategoryRequestDTO {

    @Schema(example = "VIP",
            description = "Display name of the category shown to customers. Must be unique among the "
                    + "event's live categories.")
    @NotBlank(message = "Category name is required")
    @Size(max = 200, message = "Category name must be at most 200 characters")
    private String name;

    @Schema(example = "Premium front-row seats with complimentary drink and priority access.", nullable = true)
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Schema(example = "120.00", description = "Ticket price per seat in the event's currency. Must be greater than 0.")
    @NotNull(message = "Price is required")
    // inclusive=false rejects 0 too — same rule as create: a $0 category isn't a
    // domain we support. Service re-checks as defence-in-depth for S2S callers.
    @DecimalMin(value = "0.00", inclusive = false, message = "Price must be greater than 0")
    private BigDecimal price;
}
