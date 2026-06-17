package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S2S result of "may this team member scan tickets for this event?". Encodes
 * the assignment product rule (no assignment rows = organizer-wide = allowed)
 * so booking-service doesn't need to know it — it just reads {@code allowed}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanAccessDTO {
    private boolean allowed;
}
