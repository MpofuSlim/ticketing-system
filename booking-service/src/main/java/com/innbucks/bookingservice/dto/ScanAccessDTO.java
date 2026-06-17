package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S2S response from user-service's {@code /users/internal/team-members/{uuid}/can-scan/{eventId}}.
 * {@code allowed} already encodes the assignment product rule (no assignment
 * rows = organizer-wide = allowed), so booking-service just reads the flag.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanAccessDTO {
    private boolean allowed;
}
