package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Mirrors booking-service's CategoryTierCountDTO: active (PENDING + CONFIRMED)
 * tickets held or sold at one price tier of one category, and those refunded
 * after payment (which count when the tier does not reopen on refund). Used for display
 * only (a tier's sold / remaining / SOLD_OUT); booking-service prices each
 * ticket from its own count, under its stock lock.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CategoryTierCountDTO {
    private UUID categoryId;
    private UUID priceTierId;
    private long count;
    private long refunded;
}
