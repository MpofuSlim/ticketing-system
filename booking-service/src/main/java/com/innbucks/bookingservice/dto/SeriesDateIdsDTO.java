package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * event-service's answer to "which events are this series' dates, and whose
 * series is it?" ({@code GET /events/internal/series-dates}, TIC-72). Always
 * answered 200, an unknown series with {@code found = false}, so a null answer
 * here only ever means "could not ask".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeriesDateIdsDTO {
    private UUID seriesId;
    private boolean found;
    private UUID tenantUserUuid;
    private List<UUID> eventIds;
}
