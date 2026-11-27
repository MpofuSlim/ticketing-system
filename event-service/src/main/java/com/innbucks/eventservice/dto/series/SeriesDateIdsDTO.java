package com.innbucks.eventservice.dto.series;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * booking-service's question for a report per series (TIC-72): which events
 * are this series' dates, and whose series is it? Always a 200, an unknown
 * series included ({@code found: false}), so the caller's Feign fallback can
 * only mean "could not ask".
 */
@Schema(name = "SeriesDateIds", description = "A series' dates and its owner (internal).")
public record SeriesDateIdsDTO(
        UUID seriesId,
        @Schema(description = "False: no such series. Every other field is then null or empty.")
        boolean found,
        @Schema(nullable = true, description = "The organizer the series belongs to.")
        UUID tenantUserUuid,
        @Schema(description = "Every date the series has made, deleted or not, never the template.")
        List<UUID> eventIds) {

    public static SeriesDateIdsDTO notFound(UUID seriesId) {
        return new SeriesDateIdsDTO(seriesId, false, null, List.of());
    }
}
