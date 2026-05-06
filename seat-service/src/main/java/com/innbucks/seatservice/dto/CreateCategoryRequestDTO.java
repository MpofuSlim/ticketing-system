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

    @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            description = "UUID of the event this category belongs to.")
    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @Schema(example = "VIP",
            description = "Display name of the category shown to customers (e.g. VIP, General Admission, VVIP).")
    @NotBlank(message = "Category name is required")
    private String name;

    @Schema(example = "Premium front-row seats with complimentary drink and priority access.", nullable = true)
    private String description;

    @Schema(example = "100.00", description = "Ticket price per seat in the event's currency.")
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price cannot be negative")
    private BigDecimal price;

    // sections = [{section:"A", seatCount:25}, {section:"B", seatCount:25}]
    @Schema(description = "One entry per physical section. Total seats = sum of all seatCount values.")
    @NotEmpty(message = "At least one section is required")
    private List<@Valid SectionSeatConfigDTO> sections;
}
