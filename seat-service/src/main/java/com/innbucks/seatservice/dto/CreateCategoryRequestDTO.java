package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Schema(name = "CreateCategoryRequest",
        description = "Defines a new seat category for an event, including its sections and per-seat count.")
public class CreateCategoryRequestDTO {

    public static final int MAX_SECTIONS_PER_CATEGORY = 100;

    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            description = "UUID of the event this category belongs to.")
    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @Schema(example = "VIP",
            description = "Display name of the category shown to customers (e.g. VIP, General Admission, VVIP).")
    @NotBlank(message = "Category name is required")
    @Size(max = 255, message = "Category name must be at most 255 characters")
    private String name;

    @Schema(example = "Premium front-row seats with complimentary drink and priority access.", nullable = true)
    @Size(max = 255, message = "Description must be at most 255 characters")
    private String description;

    @Schema(example = "100.00", description = "Ticket price per seat in the event's currency. Must be greater than 0.")
    @NotNull(message = "Price is required")
    // inclusive=false rejects 0 too — a $0 seat category isn't a domain we
    // support (an organizer running a free event still needs a price set, even
    // if they manually refund off-platform). The previous "0.00 minimum"
    // allowed both 0 and slight negatives through to the DB.
    @DecimalMin(value = "0.00", inclusive = false,
            message = "Price must be greater than 0")
    private BigDecimal price;

    @Schema(description = "One entry per physical section. Total seats = sum of all seatCount values. "
            + "At most 100 sections per request; sum of seatCount is further capped at 500,000 server-side.")
    @NotEmpty(message = "At least one section is required")
    @Size(max = MAX_SECTIONS_PER_CATEGORY, message = "At most 100 sections per category")
    private List<@Valid SectionSeatConfigDTO> sections;
}
