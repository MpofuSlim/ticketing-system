package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S2S response from user-service's {@code /users/internal/team-members/{uuid}/can-scan/{eventId}}.
 * {@code allowed} already encodes the assignment rule, so booking-service just
 * reads the flag.
 *
 * <p><b>That rule is deny-by-default:</b> true only when an assignment row
 * exists for this exact (member, event) pair. A member with no rows scans
 * nothing. Earlier comments here described the original "no rows =
 * organizer-wide" behaviour, which {@code 19ec675f} removed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanAccessDTO {
    private boolean allowed;
}
