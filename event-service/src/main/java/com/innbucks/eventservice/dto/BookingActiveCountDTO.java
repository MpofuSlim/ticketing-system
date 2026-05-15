package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

// Mirrors booking-service's EventActiveCountDTO. We only consume eventId/count
// to compute availableTickets — extra fields are tolerated for forward
// compatibility.
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingActiveCountDTO {
    private UUID eventId;
    private long count;
}
