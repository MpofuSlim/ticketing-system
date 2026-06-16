package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Mirrors booking-service's CategoryActiveCountDTO — the count of active
 * (PENDING + CONFIRMED) booking items for one seat category. seat-service
 * subtracts this from {@code totalSeats} to surface a live {@code availableSeats}
 * on every category read. Only the fields we read are declared; extra fields
 * are tolerated for forward compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CategoryActiveCountDTO {
    private UUID categoryId;
    private long count;
}
