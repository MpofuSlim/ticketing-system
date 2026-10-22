package com.innbucks.eventservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSeatCategoryResponseDTO {
    private String name;
    private String description;
    /** The category's full price. */
    private BigDecimal categoryPrice;
    /**
     * What one ticket costs if booked now: the ACTIVE price tier's price, else
     * {@code categoryPrice}. booking-service prices each ticket by the same rule.
     */
    private BigDecimal currentPrice;
    /** The ACTIVE tier's name, or null when the full price applies. */
    private String currentTierName;
    /** The category's price tiers in the order they apply; empty when it has none. */
    private List<EventPriceTierDTO> priceTiers;
    private List<EventSectionResponseDTO> sections;
}
