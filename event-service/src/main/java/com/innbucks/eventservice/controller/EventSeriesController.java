package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.ReviewStatus;
import com.innbucks.eventservice.dto.series.CreateSeriesRequestDTO;
import com.innbucks.eventservice.dto.series.EventSeriesResponseDTO;
import com.innbucks.eventservice.dto.series.FutureDatesRequestDTO;
import com.innbucks.eventservice.dto.series.FuturePriceRequestDTO;
import com.innbucks.eventservice.dto.series.SeriesChangeDTO;
import com.innbucks.eventservice.dto.series.SeriesScheduleRequestDTO;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.security.AuthenticatedCaller;
import com.innbucks.eventservice.security.JwtFilter;
import com.innbucks.eventservice.service.EventSeriesService;
import com.innbucks.eventservice.service.SeriesEditService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Recurring events (V24, TIC-70). A series is a schedule and a template event;
 * its dates are ordinary events made from the template inside the booking
 * window. Changed after set-up through the edit endpoints (TIC-71). Off by
 * default per cell ({@code EVENTS_SERIES_ENABLED}): every endpoint here is
 * then a 404.
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
    private final SeriesEditService editService;

    public EventSeriesController(EventSeriesService seriesService, SeriesEditService editService) {
        this.seriesService = seriesService;
        this.editService = editService;
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

    // ---- Changing a series (TIC-71) --------------------------------------------

    private static final String WRITERS = "hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')";

    private static final String CHANGE_SCHEDULE_EXAMPLE = """
            {"code":"200 OK","message":"Schedule changed","data":{
              "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
                "templateEventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "tenantUserUuid":"9f0e1d2c-3b4a-4958-8776-655443322110","status":"ACTIVE","reviewStatus":"APPROVED",
                "rejectionReason":null,"title":"Friday Comedy Night","venue":"Theatre in the Park, Harare",
                "schedule":{"daysOfWeek":["SATURDAY"],"startTimes":["19:00"],"durationMinutes":120,
                  "startDate":"2026-12-04","endDate":"2027-03-26","skipDates":["2026-12-26"],"extraStarts":[],
                  "bookingWindowDays":60,"salesOpenMinutesBefore":20160,"salesCloseMinutesBefore":30},
                "datesInProgress":0,"nextStarts":["2026-12-05T19:00:00+02:00","2026-12-12T19:00:00+02:00"],
                "upcomingDates":[{"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                  "startDateTime":"2026-12-04T19:00:00+02:00","endDateTime":"2026-12-04T21:00:00+02:00",
                  "salesStatus":"ON_SALE","active":true,"salesPaused":false}],
                "upcomingDateCount":1,"createdAt":"2026-11-26T11:00:00+02:00","updatedAt":"2026-11-27T09:15:00+02:00"},
              "dates":[
                {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","startDateTime":"2026-12-04T19:00:00+02:00",
                  "outcome":"KEPT_HAS_BOOKINGS","activeBookings":3},
                {"eventId":"8d2e3f4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a","startDateTime":"2026-12-11T19:00:00+02:00",
                  "outcome":"REMOVED","activeBookings":0}]}}""";

    private static final String ENDED_409 = """
            {"code":"409 CONFLICT","message":"This series has ended. Set up a new one.","data":null}""";
    private static final String CHANGED_409 = """
            {"code":"409 CONFLICT","message":"This series was changed by someone else just now. Refresh it and try again.","data":null}""";
    private static final String COUNTS_503 = """
            {"code":"503 SERVICE_UNAVAILABLE","message":"We couldn't check the bookings just now, so nothing was changed. Try again in a minute.","data":null}""";
    private static final String NOT_FOUND_404 = """
            {"code":"404 NOT_FOUND","message":"Series not found","data":null}""";
    private static final String FORBIDDEN_403 = """
            {"code":"403 FORBIDDEN","message":"Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)","data":null}""";

    @PutMapping("/{seriesId}/schedule")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Change a series' schedule",
            description = """
                    Replaces the whole schedule (send every field, as on create; GET the series for its current one).
                    Dates not made yet follow it within five minutes. A date already made that the new schedule no
                    longer has is REMOVED when nobody holds a ticket for it, and KEPT_HAS_BOOKINGS when someone does: it
                    goes ahead as before, and can be moved or changed like any event. Dates the schedule still has keep
                    their own length and sales window; durationMinutes and the sales offsets apply to new dates. A
                    passed extra start may be sent back unchanged. Bookings are counted before anything is removed; if
                    they can't be, nothing changes (503).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed; dates lists every date it removed or kept",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Changed",
                            value = CHANGE_SCHEDULE_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "A field is wrong; nothing changed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Field", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "startTimes[0]":"Use the 24-hour time, like 19:00.",
                                      "extraStarts[0]":"That start has passed."}}"""),
                            @ExampleObject(name = "No date", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "startDate":"This schedule makes no date from now on. Check the days, the dates and the skipped days."}}""")})),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Ended, or changed by someone else meanwhile",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = ENDED_409),
                            @ExampleObject(name = "Changed meanwhile", value = CHANGED_409)})),
            @ApiResponse(responseCode = "503", description = "Bookings could not be counted; nothing changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Try again",
                            value = COUNTS_503)))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> updateSchedule(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            @Valid @RequestBody SeriesScheduleRequestDTO request,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.updateSchedule(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId, request);
        return ResponseEntity.ok(ApiResult.ok("Schedule changed", changed));
    }

    @PutMapping("/{seriesId}/future-dates")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Change every future date of a series",
            description = """
                    Changes the template and every date that has not started. Every field is optional; send at least
                    one. moveStartTime moves every date at one of the schedule's start times to another time the same
                    day (an extra start is never moved) and changes the schedule to match; a date whose new time is
                    taken by another date of the series (NOT_MOVED_TAKEN) or has already passed (NOT_MOVED_PASSED)
                    stays where it is. Each date keeps its length and its sales window's distance from the start.
                    Everyone booked on a date that MOVED, or whose venue changed, is sent the same message as for a
                    change to one event (activeBookings says how many were booked, when that could be counted).
                    A date given its own description (PUT /events/{id}) keeps it: keptOwn ["description"] says so,
                    and a date with nothing else to change is KEPT_OWN. Its other fields still change.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Moved",
                            value = """
                                    {"code":"200 OK","message":"Future dates changed","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ACTIVE",
                                        "title":"Friday Comedy Night","venue":"Reps Theatre, Harare",
                                        "schedule":{"daysOfWeek":["FRIDAY"],"startTimes":["19:30","21:30"],
                                          "durationMinutes":120,"startDate":"2026-12-04","endDate":"2027-03-26",
                                          "skipDates":["2026-12-25"],"extraStarts":["2026-12-24T15:00"],
                                          "bookingWindowDays":60,"salesOpenMinutesBefore":20160,"salesCloseMinutesBefore":30}},
                                      "dates":[
                                        {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                          "startDateTime":"2026-12-04T19:30:00+02:00","outcome":"MOVED","activeBookings":3},
                                        {"eventId":"9e8d7c6b-5a49-4382-a716-152433425160",
                                          "startDateTime":"2026-12-04T21:30:00+02:00","outcome":"UPDATED",
                                          "keptOwn":["description"]}]}}"""))),
            @ApiResponse(responseCode = "400", description = "Nothing to change, or a field is wrong",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Nothing", value = """
                                    {"code":"400 BAD_REQUEST","message":"Send at least one change.","data":null}"""),
                            @ExampleObject(name = "Move", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "moveStartTime.from":"The schedule has no start at 18:00.",
                                      "moveStartTime.to":"The schedule already has a start at 21:30."}}""")})),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Ended, or changed by someone else meanwhile",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = ENDED_409),
                            @ExampleObject(name = "Changed meanwhile", value = CHANGED_409)}))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> updateFutureDates(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            @Valid @RequestBody FutureDatesRequestDTO request,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.updateFutureDates(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId, request);
        return ResponseEntity.ok(ApiResult.ok("Future dates changed", changed));
    }

    private static final String BANNER_CHANGED_200 = """
            {"code":"200 OK","message":"Series banner updated","data":{
              "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
                "templateEventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","status":"ACTIVE",
                "title":"Friday Comedy Night","venue":"Theatre in the Park, Harare"},
              "dates":[
                {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","startDateTime":"2026-12-04T19:00:00+02:00",
                  "outcome":"UPDATED"},
                {"eventId":"9e8d7c6b-5a49-4382-a716-152433425160","startDateTime":"2027-01-01T19:00:00+02:00",
                  "outcome":"KEPT_OWN","keptOwn":["banner"]}]}}""";

    // POST, not PUT: Cloudflare's WAF refuses PUT with a multipart body before
    // it reaches us (CLAUDE.md, "Cloudflare blocks PUT + multipart").
    @PostMapping(value = "/{seriesId}/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITERS)
    @Operation(summary = "Set the series' banner on every future date",
            description = """
                    Sets the banner on the series' template and on every date that has not started and still shows the
                    series' banner (UPDATED). A date given its own banner with POST /events/{id}/banner keeps it
                    (KEPT_OWN, keptOwn ["banner"]). Dates made later copy the template, so they get the new banner.
                    Send multipart/form-data with one eventBanner part: JPG, PNG or WEBP, under 10 MB, checked by its
                    bytes. Use POST: PUT with a multipart body is blocked in front of this server.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What happened on each date",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Updated",
                            value = BANNER_CHANGED_200))),
            @ApiResponse(responseCode = "400", description = "No file, too large, or not a JPG/PNG/WEBP image",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No file", value = """
                                    {"code":"400 BAD_REQUEST","message":"Please choose an image to upload.","data":null}"""),
                            @ExampleObject(name = "Too large", value = """
                                    {"code":"400 BAD_REQUEST","message":"That image is too large. Please use one under 10 MB.","data":null}"""),
                            @ExampleObject(name = "Wrong type", value = """
                                    {"code":"400 BAD_REQUEST","message":"Please upload a JPG, PNG, or WEBP image.","data":null}"""),
                            @ExampleObject(name = "Not really an image", value = """
                                    {"code":"400 BAD_REQUEST","message":"Please upload a valid image file (JPG, PNG, or WEBP).","data":null}""")})),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Ended, or changed by someone else meanwhile",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = ENDED_409),
                            @ExampleObject(name = "Changed meanwhile", value = CHANGED_409)}))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> replaceBanner(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            @RequestPart(value = "eventBanner", required = false) MultipartFile eventBanner,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.replaceSeriesBanner(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId, eventBanner);
        return ResponseEntity.ok(ApiResult.ok("Series banner updated", changed));
    }

    @DeleteMapping("/{seriesId}/banner")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Remove the series' banner from every future date",
            description = """
                    Removes the banner from the series' template and from every date that has not started and still
                    shows the series' banner. A date with its own banner keeps it (KEPT_OWN). Safe to repeat.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What happened on each date",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Removed",
                            value = """
                                    {"code":"200 OK","message":"Series banner removed","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ACTIVE"},
                                      "dates":[
                                        {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                          "startDateTime":"2026-12-04T19:00:00+02:00","outcome":"UPDATED"}]}}"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Ended, or changed by someone else meanwhile",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = ENDED_409),
                            @ExampleObject(name = "Changed meanwhile", value = CHANGED_409)}))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> clearBanner(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.clearSeriesBanner(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        return ResponseEntity.ok(ApiResult.ok("Series banner removed", changed));
    }

    @PutMapping("/{seriesId}/future-prices")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Change a ticket type's price on every future date",
            description = """
                    Sets the full price of the ticket type with this name on the template and every date that has not
                    started. Tickets already sold keep the price they were sold at. The rules of a price edit on one
                    date apply to each: a date with a price tier still open at or above the new price is PRICE_REFUSED
                    (message says which tier), and the other dates still change. Each change is in that ticket type's
                    price history. Public event pages show the new price within about 30 seconds.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What happened on each date",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Changed",
                            value = """
                                    {"code":"200 OK","message":"Price changed","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ACTIVE",
                                        "title":"Friday Comedy Night"},
                                      "dates":[
                                        {"eventId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","template":true,
                                          "startDateTime":null,"outcome":"PRICE_UPDATED"},
                                        {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                          "startDateTime":"2026-12-04T19:00:00+02:00","outcome":"PRICE_UPDATED"},
                                        {"eventId":"9e8d7c6b-5a49-4382-a716-152433425160",
                                          "startDateTime":"2026-12-11T19:00:00+02:00","outcome":"PRICE_REFUSED",
                                          "message":"The ticket price must be higher than the price tier \\"Early bird\\" (150.00). Lower or remove that tier first."}]}}"""))),
            @ApiResponse(responseCode = "400", description = "A field is wrong, or no date has that ticket type",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Field", value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "price":"Use at most 2 decimals."}}"""),
                            @ExampleObject(name = "No such ticket type", value = """
                                    {"code":"400 BAD_REQUEST","message":"This series has no ticket type called \\"VVIP\\".","data":null}""")})),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Ended",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Ended",
                            value = ENDED_409))),
            @ApiResponse(responseCode = "503", description = "Ticket prices could not be reached",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Try again",
                            value = """
                                    {"code":"503 SERVICE_UNAVAILABLE","message":"We couldn't reach the ticket prices just now. Try again in a minute: any price already changed stays as it is.","data":null}""")))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> setFuturePrice(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            @Valid @RequestBody FuturePriceRequestDTO request,
            Authentication authentication) {
        UUID organizer = AuthenticatedCaller.organizerUuid(authentication);
        // Recorded in each ticket type's price history: the organizer's uuid, as
        // seat-service's own auditor stamps an organizer, or a staff member's email.
        String actor = organizer != null ? organizer.toString() : authentication.getName();
        SeriesChangeDTO changed = editService.setFuturePrice(organizer,
                AuthenticatedCaller.isPlatformStaff(authentication), actor, seriesId, request);
        return ResponseEntity.ok(ApiResult.ok("Price changed", changed));
    }

    @PostMapping("/{seriesId}/dates/{eventId}/skip")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Skip one date of a series",
            description = """
                    Removes a date that has not started, when nobody holds a ticket for it. The series never makes that
                    start again. A date people hold tickets for can't be skipped (409): change it with the event
                    endpoints, or contact them first. Cancelling a booked date with refunds is not available yet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Skipped",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Skipped",
                            value = """
                                    {"code":"200 OK","message":"Date skipped","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ACTIVE",
                                        "title":"Friday Comedy Night"},
                                      "dates":[{"eventId":"8d2e3f4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a",
                                        "startDateTime":"2026-12-11T19:00:00+02:00","outcome":"REMOVED","activeBookings":0}]}}"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series or date, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Series", value = NOT_FOUND_404),
                            @ExampleObject(name = "Date", value = """
                                    {"code":"404 NOT_FOUND","message":"Date not found","data":null}""")})),
            @ApiResponse(responseCode = "409", description = "People hold tickets for it, or it has started",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Booked", value = """
                                    {"code":"409 CONFLICT","message":"People hold tickets for this date (3 active bookings), so it can't be skipped. Change the date instead, or contact them first.","data":null}"""),
                            @ExampleObject(name = "Started", value = """
                                    {"code":"409 CONFLICT","message":"This date has already started.","data":null}""")})),
            @ApiResponse(responseCode = "503", description = "Bookings could not be counted; nothing changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Try again",
                            value = COUNTS_503)))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> skipDate(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            @Parameter(description = "The date's event id") @PathVariable UUID eventId,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.skipDate(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId, eventId);
        return ResponseEntity.ok(ApiResult.ok("Date skipped", changed));
    }

    @PostMapping("/{seriesId}/pause")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Pause a series",
            description = """
                    No new dates are made, and every date that has not ended stops selling: its sales close now
                    (salesStatus SALES_CLOSED, salesPaused true). The dates stay listed, tickets already sold stay valid
                    and are scanned at the gate as usual. Resume opens them again. Only a live (ACTIVE) series can be
                    paused.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paused",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Paused",
                            value = """
                                    {"code":"200 OK","message":"Series paused","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"PAUSED",
                                        "title":"Friday Comedy Night"},
                                      "dates":[{"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                        "startDateTime":"2026-12-04T19:00:00+02:00","outcome":"SALES_PAUSED"}]}}"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Not live, already paused, or ended",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not live", value = """
                                    {"code":"409 CONFLICT","message":"Only a live series can be paused. Publish it first.","data":null}"""),
                            @ExampleObject(name = "Already paused", value = """
                                    {"code":"409 CONFLICT","message":"This series is already paused.","data":null}"""),
                            @ExampleObject(name = "Ended", value = ENDED_409)}))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> pause(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.pause(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        return ResponseEntity.ok(ApiResult.ok("Series paused", changed));
    }

    @PostMapping("/{seriesId}/resume")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Resume a paused series",
            description = "Dates are made again, and every date the pause closed sells again with its own sales "
                    + "window. A series staff rejected while it was paused is resumed only after it is published "
                    + "and approved again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resumed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Resumed",
                            value = """
                                    {"code":"200 OK","message":"Series resumed","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ACTIVE",
                                        "title":"Friday Comedy Night"},
                                      "dates":[{"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                        "startDateTime":"2026-12-04T19:00:00+02:00","outcome":"SALES_RESUMED"}]}}"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Not paused, rejected, or ended",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not paused", value = """
                                    {"code":"409 CONFLICT","message":"This series isn't paused.","data":null}"""),
                            @ExampleObject(name = "Rejected", value = """
                                    {"code":"409 CONFLICT","message":"This series was rejected. Publish it again for review, then resume it.","data":null}"""),
                            @ExampleObject(name = "Ended", value = ENDED_409)}))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> resume(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.resume(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        return ResponseEntity.ok(ApiResult.ok("Series resumed", changed));
    }

    @PostMapping("/{seriesId}/end")
    @PreAuthorize(WRITERS)
    @Operation(summary = "End a series",
            description = """
                    Ends the series for good: no new dates. Every future date nobody holds a ticket for is REMOVED;
                    a date people hold tickets for is KEPT_HAS_BOOKINGS and goes ahead as before (a paused one sells
                    again). Bookings are counted before anything changes; if they can't be, nothing changes (503).
                    DELETE /events/{templateEventId} still ends a series too, without removing any date.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ended",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Ended",
                            value = """
                                    {"code":"200 OK","message":"Series ended","data":{
                                      "series":{"seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","status":"ENDED",
                                        "title":"Friday Comedy Night"},
                                      "dates":[
                                        {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                          "startDateTime":"2026-12-04T19:00:00+02:00","outcome":"KEPT_HAS_BOOKINGS","activeBookings":3},
                                        {"eventId":"8d2e3f4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a",
                                          "startDateTime":"2026-12-11T19:00:00+02:00","outcome":"REMOVED","activeBookings":0}]}}"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer, a product manager or a super admin",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN_403))),
            @ApiResponse(responseCode = "404", description = "No such series, someone else's, or switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = NOT_FOUND_404))),
            @ApiResponse(responseCode = "409", description = "Already ended, or changed by someone else meanwhile",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Ended", value = ENDED_409),
                            @ExampleObject(name = "Changed meanwhile", value = CHANGED_409)})),
            @ApiResponse(responseCode = "503", description = "Bookings could not be counted; nothing changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Try again",
                            value = COUNTS_503)))
    })
    public ResponseEntity<ApiResult<SeriesChangeDTO>> end(
            @Parameter(description = "Series id") @PathVariable UUID seriesId,
            Authentication authentication) {
        SeriesChangeDTO changed = editService.end(AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication), seriesId);
        return ResponseEntity.ok(ApiResult.ok("Series ended", changed));
    }
}
