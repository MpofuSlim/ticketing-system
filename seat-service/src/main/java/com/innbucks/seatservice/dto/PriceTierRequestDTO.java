package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One price tier as an organizer sends it: a name, a price, and when it closes —
 * at {@code endsAt}, after {@code quantity} tickets, or whichever comes first.
 *
 * <p>{@code endsAt} is a string so the service can tell a wall clock from an
 * instant: with no zone it is the market's local time, as an organizer types it
 * (the event-time rule); with {@code Z} or an offset it is that exact instant.
 */
@Data
@Schema(name = "PriceTierRequest",
        description = "A lower price the category sells at until endsAt, or for the first quantity "
                + "tickets, whichever comes first.")
public class PriceTierRequestDTO {

    @Schema(example = "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", nullable = true,
            description = "Send the id of a tier you are keeping when replacing the list, so its "
                    + "tickets sold still count toward its quantity. Omit for a new tier.")
    private UUID id;

    @Schema(example = "Early bird", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Shown to customers and in reports. Unique within the category.")
    @NotBlank(message = "Tier name is required")
    @Size(max = 60, message = "Tier name must be at most 60 characters")
    private String name;

    @Schema(example = "100.00", requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Ticket price while this tier is open. Greater than 0 and lower than the "
                    + "category's price. At most 2 decimal places.")
    @NotNull(message = "Tier price is required")
    @DecimalMin(value = "0.00", inclusive = false, message = "Tier price must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "Tier price can have at most 2 decimal places")
    private BigDecimal price;

    @Schema(example = "2026-09-05T18:00:00", nullable = true,
            description = "When the tier opens, in the organizer's local time with no zone (or an instant "
                    + "with Z / an offset). Omit to open at once. Must be before endsAt.")
    @Size(max = 40, message = "Tier start must be a date and time like 2026-09-05T18:00:00")
    private String startsAt;

    @Schema(example = "2026-09-08T23:59:59", nullable = true,
            description = "When the tier closes. The organizer's local time with no zone (send "
                    + "2026-09-08T23:59:59 to run through 8 September); a value with Z or an offset is that "
                    + "exact instant. Set endsAt, quantity, or both.")
    @Size(max = 40, message = "Tier end must be a date and time like 2026-09-08T23:59:59")
    private String endsAt;

    @Schema(example = "100", nullable = true,
            description = "How many tickets sell at this tier before it closes. Set endsAt, quantity, or both.")
    @Positive(message = "Tier quantity must be at least 1")
    private Integer quantity;

    @Schema(example = "4", nullable = true,
            description = "Most tickets one customer (by phone, else email) can hold at this tier; tickets "
                    + "past it are priced at the next open tier. Omit for no cap.")
    @Positive(message = "Tier limit per customer must be at least 1")
    private Integer maxPerCustomer;

    @Schema(example = "true", nullable = true,
            description = "Whether a ticket refunded after payment frees its place in the quantity, so the "
                    + "next buyer gets it at this price. Defaults to true. An unpaid hold that lapses always "
                    + "frees its place.")
    private Boolean refundsReopen;
}
