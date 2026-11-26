package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.security.AuthenticatedCaller;
import com.innbucks.eventservice.security.JwtFilter;
import com.innbucks.eventservice.service.EventSeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Recurring events (V24, TIC-70). A series is a schedule and a template event;
 * its dates are ordinary events made from the template inside the booking
 * window. Off by default per cell ({@code EVENTS_SERIES_ENABLED}): every
 * endpoint here is then a 404.
 */
@RestController
@RequestMapping("/events/series")
@Validated
@Slf4j
@Tag(name = "Event series", description = "Recurring events: one set-up, a date for every start in the booking "
        + "window. Set the template up with the ordinary event endpoints.")
public class EventSeriesController {

    static final String SUBMITTED_MESSAGE = "Series submitted for review";
    private static final int MAX_PAGE_SIZE = 100;

    private final EventSeriesService seriesService;

    public EventSeriesController(EventSeriesService seriesService) {
        this.seriesService = seriesService;
    }

    private static final String SERIES_EXAMPLE = """
            {
              "seriesId": "5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
              "templateEventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "tenantUserUuid": "9f0e1d2c-3b4a-4958-8776-655443322110",
              "status": "DRAFT",
              "reviewStatus": "DRAFT",
              "rejectionReason": null,
              "title": "Friday Comedy Night",
              "venue": "Theatre in the Park, Harare",
              "schedule": {
                "daysOfWeek": ["FRIDAY"],
                "startTimes": ["19:00", "21:30"],
                "durationMinutes": 120,
                "startDate": "2026-12-04",
                "endDate": "2027-03-26",
                "skipDates": ["2026-12-25"],
                "extraStarts": ["2026-12-24T15:00"],
                "bookingWindowDays": 60,
                "salesOpenMinutesBefore": 20160,
                "salesCloseMinutesBefore": 30
              },
              "datesInProgress": 0,
              "nextStarts": ["2026-12-04T19:00:00+02:00", "2026-12-04T21:30:00+02:00", "2026-12-11T19:00:00+02:00"],
              "upcomingDates": [],
              "upcomingDateCount": 0,
              "createdAt": "2026-11-26T11:00:00+02:00",
              "updatedAt": null
            }""";

    @PostMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Set up a recurring series",
            description = """
                    Creates the series in DRAFT with its template event (seriesState TEMPLATE) and no dates. Then set
                    the template up with the ordinary endpoints, using templateEventId: seat categories with their
                    price tiers and add-ons (seat-service), booking questions, banner, gate scanners and sales agents.
                    Publish with POST /events/series/{seriesId}/publish. Each date made afterwards copies the template
                    as it is then. Times, dates and days are the market's local wall clock.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Created",
                            value = "{\"code\":\"201 CREATED\",\"message\":\"Series created\",\"data\":"
                                    + SERIES_EXAMPLE + "}"))),
            @ApiResponse(responseCode = "400", description = "A field is wrong, or the caller is staff",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Schedule", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "startTimes[1]":"Use the 24-hour time, like 19:00.",
                                      "skipDates[0]":"Use a date like 2026-12-25."}}"""),
                            @ExampleObject(name = "Too many dates", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "bookingWindowDays":"This schedule makes 520 dates in 180 days; at most 400. Shorten the booking window, or use fewer days or start times."}}"""),
                            @ExampleObject(name = "No date", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "startDate":"This schedule makes no date from now on. Check the days, the dates and the skipped days."}}"""),
                            @ExampleObject(name = "Staff", value = """
                                    {"code":"400 BAD_REQUEST","message":"A series must belong to an event organizer. Sign in as the organizer to set one up.","data":null}""")})),
            @ApiResponse(responseCode = "401", description = "No or bad token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unauthorized",
                            value = "{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Invalid token\",\"data\":null}"))),
            @ApiResponse(responseCode = "404", description = "Recurring series are switched off in this cell",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Off",
                            value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Not found\",\"data\":null}"))),
            @ApiResponse(responseCode = "409", description = "The organizer already has an event with the same title "
                    + "and venue at the first start",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Duplicate",
                            value = "{\"code\":\"409 CONFLICT\",\"message\":\"An event with the same title, venue and "
                                    + "date already exists\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventSeriesResponseDTO>> create(
            @Valid @RequestBody CreateSeriesRequestDTO request,
            @RequestAttribute(name = JwtFilter.COUNTRY_ATTRIBUTE, required = false) String country,
            Authentication authentication) {
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        EventSeriesResponseDTO created = seriesService.create(organizerUuid, country, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Series created", created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
    @Operation(summary = "List series",
            description = "The organizer's own series, newest first; platform staff see every organizer's. The "
                    + "detail-only fields (nextStarts, upcomingDates, upcomingDateCount, lastDateStart, "
                    + "datesInProgress) are absent here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of series",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Page",
                            value = """
                                    {"code":"200 OK","message":"Series retrieved","data":{"content":[{
                                      "seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
                                      "templateEventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                      "tenantUserUuid":"9f0e1d2c-3b4a-4958-8776-655443322110",
                                      "status":"ACTIVE","reviewStatus":"APPROVED","rejectionReason":null,
                                      "title":"Friday Comedy Night","venue":"Theatre in the Park, Harare",
                                      "schedule":{"daysOfWeek":["FRIDAY"],"startTimes":["19:00","21:30"],
                                        "durationMinutes":120,"startDate":"2026-12-04","endDate":"2027-03-26",
                                        "skipDates":["2026-12-25"],"extraStarts":["2026-12-24T15:00"],
                                        "bookingWindowDays":60,"salesOpenMinutesBefore":20160,"salesCloseMinutesBefore":30},
                                      "createdAt":"2026-11-26T11:00:00+02:00","updatedAt":"2026-11-26T11:20:00+02:00"}],
                                      "totalElements":1,"totalPages":1,"number":0,"size":20}}"""))),
            @ApiResponse(responseCode = "404", description = "Switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Off",
                            value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<Page<EventSeriesResponseDTO>>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            Authentication authentication) {
        Page<EventSeriesResponseDTO> series = seriesService.list(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), page, size);
        return ResponseEntity.ok(ApiResult.ok("Series retrieved", series));
    }

    @GetMapping("/{seriesId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
    @Operation(summary = "One series, with its next starts and upcoming dates",
            description = "nextStarts previews what the schedule makes (whether or not the dates exist yet); "
                    + "upcomingDates lists the dates that exist and have not ended (at most 50). Each date is an "
                    + "ordinary event: read or change it with /events/{eventId}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The series",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Series",
                            value = "{\"code\":\"200 OK\",\"message\":\"Series retrieved\",\"data\":"
                                    + SERIES_EXAMPLE + "}"))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Series not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventSeriesResponseDTO>> get(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        EventSeriesResponseDTO series = seriesService.get(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        return ResponseEntity.ok(ApiResult.ok("Series retrieved", series));
    }

    @PostMapping("/{seriesId}/publish")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')")
    @Operation(summary = "Publish a series",
            description = """
                    The same call as PUT /events/{templateEventId}/activate, with the same review rules. Review step
                    off: the series goes ACTIVE and its dates appear within five minutes. Review step on: the
                    organizer's publish submits the template for review (status stays DRAFT, reviewStatus SUBMITTED)
                    and staff approve it with PUT /events/{templateEventId}/approve. Unpublish with
                    PUT /events/{templateEventId}/deactivate and end the series with DELETE /events/{templateEventId}:
                    both stop NEW dates only; dates already made are ordinary events and stay as they are.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Published, or submitted for review",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Published", value = "{\"code\":\"200 OK\",\"message\":\"Series "
                                    + "published\",\"data\":" + SERIES_EXAMPLE + "}"),
                            @ExampleObject(name = "Submitted", value = "{\"code\":\"200 OK\",\"message\":\"Series "
                                    + "submitted for review\",\"data\":" + SERIES_EXAMPLE + "}")})),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Series not found\",\"data\":null}"))),
            @ApiResponse(responseCode = "409", description = "Ended, rejected, or not submitted yet",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = "{\"code\":\"409 CONFLICT\",\"message\":\"This "
                                    + "series has ended. Set up a new one.\",\"data\":null}"),
                            @ExampleObject(name = "Rejected", value = "{\"code\":\"409 CONFLICT\",\"message\":\"This "
                                    + "event has been rejected by an administrator and cannot be activated\","
                                    + "\"data\":null}"),
                            @ExampleObject(name = "Not submitted", value = "{\"code\":\"409 CONFLICT\",\"message\":"
                                    + "\"The organizer hasn't submitted this event for review yet.\",\"data\":null}")}))
    })
    public ResponseEntity<ApiResult<EventSeriesResponseDTO>> publish(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        EventSeriesResponseDTO series = seriesService.publish(AuthenticatedCaller.organizerUuid(authentication),
                roleOf(authentication), AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        boolean submitted = series.getStatus() != EventSeries.Status.ACTIVE
                && series.getReviewStatus() == ReviewStatus.SUBMITTED;
        return ResponseEntity.ok(ApiResult.ok(submitted ? SUBMITTED_MESSAGE : "Series published", series));
    }

    /** SUPER_ADMIN when the caller holds it, else their first role, as the event endpoints pass it. */
    private static String roleOf(Authentication authentication) {
        boolean superAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
        if (superAdmin) {
            return "ROLE_SUPER_ADMIN";
        }
        return authentication.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse("");
    }
}
