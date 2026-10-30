package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * event-service's answer to "may this super-app account scan this event?"
 * ({@code GET /events/internal/scanner-access}). When allowed: the scanner row,
 * the organizer whose team it is, and the name the organizer typed, which is
 * what the scan records as the scanner's name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScannerAccessDTO {
    private boolean allowed;
    private UUID scannerId;
    private UUID organizerUuid;
    private String displayName;
}
