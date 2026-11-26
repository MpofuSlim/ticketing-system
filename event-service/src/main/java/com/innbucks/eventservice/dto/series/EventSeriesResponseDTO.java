package com.innbucks.eventservice.dto.series;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.entity.EventSeries;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A recurring series (V24, TIC-70), for its organizer and platform staff. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventSeries")
public class EventSeriesResponseDTO {

    @Schema(example = "5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f")
    private UUID seriesId;

    @Schema(description = "The series' set-up event. Ticket types, price tiers, add-ons, booking questions, terms, "
            + "banner and gate team are set on it with the ordinary event endpoints, and each NEW date copies "
            + "them.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID templateEventId;

    @Schema(description = "The organizer the series belongs to.", example = "9f0e1d2c-3b4a-4958-8776-655443322110")
    private UUID tenantUserUuid;

    @Schema(description = "DRAFT: no dates are made (not published yet, unpublished or rejected). ACTIVE: dates "
            + "are made inside the booking window. ENDED: the template was deleted; no new dates. PAUSED is "
            + "reserved.", example = "ACTIVE")
    private EventSeries.Status status;

    @Schema(description = "The template's review status: a series is reviewed through its template.",
            example = "APPROVED")
    private ReviewStatus reviewStatus;

    @Schema(nullable = true, description = "Why staff rejected the series, when they did.")
    private String rejectionReason;

    @Schema(example = "Friday Comedy Night")
    private String title;

    @Schema(example = "Theatre in the Park, Harare")
    private String venue;

    private SeriesScheduleDTO schedule;

    @Schema(nullable = true, description = "Detail view only: the latest start a date has been made for, deleted "
            + "dates included. Absent until the first date is made.", example = "2027-02-05T21:30:00+02:00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime lastDateStart;

    @Schema(nullable = true, description = "Detail view only: dates still being made (they appear within minutes).",
            example = "0")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long datesInProgress;

    @Schema(nullable = true, description = "Detail view only: the next starts the schedule makes from now on "
            + "(at most 10), whether or not their dates exist yet. Use it to check a schedule before publishing.",
            example = "[\"2026-12-04T19:00:00+02:00\",\"2026-12-04T21:30:00+02:00\"]")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<LocalDateTime> nextStarts;

    @Schema(nullable = true, description = "Detail view only: the series' dates that have not ended, soonest first "
            + "(at most 50; upcomingDateCount says how many there are).")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SeriesDateDTO> upcomingDates;

    @Schema(nullable = true, description = "Detail view only: how many dates have not ended.", example = "18")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long upcomingDateCount;

    @Schema(example = "2026-11-26T09:00:00+02:00")
    private LocalDateTime createdAt;

    @Schema(nullable = true, example = "2026-11-26T09:05:00+02:00")
    private LocalDateTime updatedAt;
}
