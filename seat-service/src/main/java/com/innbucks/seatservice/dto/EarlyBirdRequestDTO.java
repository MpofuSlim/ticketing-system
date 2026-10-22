package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * An early-bird offer on a seat category: tickets sell at {@code price} until
 * {@code endsAt}, then at the category's own price again. Sent inside
 * {@code POST /seat-categories} ({@code earlyBird}) and as the body of
 * {@code PUT /seat-categories/{id}/early-bird}.
 *
 * <p>{@code endsAt} is a string so the service can tell a wall clock from an
 * instant: one with no zone is the market's local time, as an organizer types
 * it (the same rule as event start and end times); one with {@code Z} or an
 * offset is that exact instant. Jackson's {@code LocalDateTime} would drop the
 * difference.
 */
@Data
@Schema(name = "EarlyBirdRequest",
        description = "A lower price the category sells at until endsAt, then its normal price again.")
public class EarlyBirdRequestDTO {

    @Schema(example = "100.00", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Early-bird ticket price, in the event's currency. Greater than 0 and lower than "
                    + "the category's price. At most 2 decimal places.")
    @NotNull(message = "Early-bird price is required")
    @DecimalMin(value = "0.00", inclusive = false, message = "Early-bird price must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "Early-bird price can have at most 2 decimal places")
    private BigDecimal price;

    @Schema(example = "2026-09-08T23:59:59", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "When the early-bird price stops applying. Send the local time the organizer "
                    + "picked, with no zone (e.g. 2026-09-08T23:59:59 to run through 8 September); the "
                    + "server reads it in the market's timezone. A value with Z or an offset is taken as "
                    + "that exact instant. Must be in the future.")
    @NotBlank(message = "Early-bird end date and time is required")
    @Size(max = 40, message = "Early-bird end must be a date and time like 2026-09-08T23:59:59")
    private String endsAt;
}
