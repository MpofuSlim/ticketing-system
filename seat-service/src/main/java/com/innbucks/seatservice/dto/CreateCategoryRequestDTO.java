package com.innbucks.seatservice.dto;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreateCategoryRequestDTO {

    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @NotBlank(message = "Category name is required")
    private String name;

    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price cannot be negative")
    private BigDecimal price;

    // Example:
    // sections = [{section:"A", seatCount:5}, {section:"B", seatCount:7}, {section:"C", seatCount:8}]
    @NotEmpty(message = "At least one section is required")
    private List<@Valid SectionSeatConfigDTO> sections;
}
