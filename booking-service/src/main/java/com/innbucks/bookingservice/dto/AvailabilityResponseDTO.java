package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

// Mirrors event-service's AvailabilityResponseDTO. Only the field we read is
// declared; extra fields are tolerated for forward compatibility.
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailabilityResponseDTO {
    private int availableTickets;
}
