package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.scan.EventScanStatsDTO;
import com.innbucks.bookingservice.dto.scan.PageResponse;
import com.innbucks.bookingservice.dto.scan.ScanAttemptDTO;
import com.innbucks.bookingservice.dto.scan.ScannerStatsDTO;
import com.innbucks.bookingservice.dto.scan.TeamStatsResponseDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.ScanReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-team-member ticket-scan reporting for the gate-staff dashboard.
 *
 * <p>Backs every endpoint on top of the {@code scan_attempts} audit log
 * (one row per scan attempt regardless of outcome — see
 * {@link com.innbucks.bookingservice.service.TicketScanService#scan}).
 *
 * <p>Two scopes are exposed:
 *
 * <ul>
 *   <li>{@code /scans/me*} — the calling user's own scan history and
 *       outcome stats. EVENT_ORGANIZER or TEAM_MEMBER may call.</li>
 *   <li>{@code /scans/events/{eventId}*} and {@code /scans/team-stats} —
 *       organizer-only views. Per-event endpoints verify the caller owns
 *       the event (organizerUuid == event.tenantUserUuid) before reading;
 *       team-stats scopes by the caller's own organizerUuid.</li>
 * </ul>
 *
 * <p>Fraud-signals view (/scans/fraud) is deferred to a follow-up PR.
 */
@RestController
@RequestMapping("/scans")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ticket Scan Reports",
     description = "Per-scanner and per-event scan-attempt reporting for the organizer dashboard.")
@SecurityRequirement(name = "bearerAuth")
public class ScanReportController {

    /** Hard cap on page size — prevents the FE from accidentally pulling the
     *  table in one shot and matches the canonical cap on
     *  {@link OrganizerReportController}'s CSV export window. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ScanReportService scanReportService;
    /** ObjectProvider so unit-tests that instantiate the controller via {@code new}
     *  don't have to wire a real EventServiceClient — the per-event endpoints
     *  explicitly check for an available client before delegating. */
    private final ObjectProvider<EventServiceClient> eventClientProvider;

    /** Shared secret presented on event-service's internal lookup (the public
     *  by-id endpoint strips tenantUserUuid for anonymous callers, which a
     *  server-side call is). Same value as INTERNAL_API_TOKEN on the
     *  event-service end of the wire. Field (not constructor) injection so
     *  {@code new}-instantiated unit tests need not supply it. */
    @org.springframework.beans.factory.annotation.Value("${innbucks.internal-api-token:}")
    private String eventInternalToken;

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER')")
    @Operation(summary = "List my scan attempts",
            description = "Returns the calling user's own scan attempts in the [from, to] window, " +
                          "newest first. Page size capped at " + MAX_PAGE_SIZE + ".")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Page of scan attempts",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Scan attempts retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "9b1f3c2e-6a47-4f7c-9d2b-1d6f0a1e5b91",
                                            "attemptedAt": "2026-06-19T19:42:11Z",
                                            "outcome": "ALLOWED",
                                            "ticketNumber": "20260619-48291X",
                                            "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                            "bookingId": "8d2c3e4a-1f5b-46a7-8c9d-0e1f2a3b4c5d",
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "scannerEmail": "tariro@harare-arena.co.zw",
                                            "scannerDisplayName": "Tariro Chikomo",
                                            "scannerUserUuid": "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 412,
                                        "totalPages": 21
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "'from' after 'to', page<0, or size out of range",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-30T00:00:00Z) must not be after 'to' (2026-06-01T00:00:00Z).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not EVENT_ORGANIZER or TEAM_MEMBER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> myScans(
            Authentication authentication,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID scannerUserUuid = requireScannerUserUuid(authentication);
        validateRange(from, to);
        validatePage(page, size);
        log.debug("GET /scans/me scanner={} from={} to={} page={} size={}",
                scannerUserUuid, from, to, page, size);
        return ResponseEntity.ok(ApiResult.ok("Scan attempts retrieved",
                scanReportService.listMyScans(scannerUserUuid, from, to, page, size)));
    }

    @GetMapping("/me/stats")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER')")
    @Operation(summary = "My scan-outcome stats",
            description = "Outcome breakdown (ALLOWED, ALREADY_REDEEMED, etc.) for the calling user " +
                          "over the [from, to] window. Every Outcome enum value is present in the response " +
                          "(zero-filled for outcomes the scanner didn't see).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Stats computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Scanner stats retrieved",
                                      "data": {
                                        "scannerUserUuid": "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b",
                                        "scannerEmail": "tariro@harare-arena.co.zw",
                                        "scannerDisplayName": "Tariro Chikomo",
                                        "from": "2026-06-01T00:00:00Z",
                                        "to": "2026-06-30T23:59:59Z",
                                        "total": 412,
                                        "byOutcome": {
                                          "ALLOWED": 380,
                                          "ALREADY_REDEEMED": 24,
                                          "WRONG_ORGANIZER": 2,
                                          "NOT_ASSIGNED_TO_EVENT": 0,
                                          "TICKET_NOT_FOUND": 6,
                                          "BOOKING_NOT_CONFIRMED": 0
                                        }
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "'from' after 'to'",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-30T00:00:00Z) must not be after 'to' (2026-06-01T00:00:00Z).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not EVENT_ORGANIZER or TEAM_MEMBER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<ScannerStatsDTO>> myStats(
            Authentication authentication,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        UUID scannerUserUuid = requireScannerUserUuid(authentication);
        validateRange(from, to);
        String email = authentication.getName();
        String displayName = resolveDisplayName(authentication);
        log.debug("GET /scans/me/stats scanner={} from={} to={}", scannerUserUuid, from, to);
        return ResponseEntity.ok(ApiResult.ok("Scanner stats retrieved",
                scanReportService.myStats(scannerUserUuid, email, displayName, from, to)));
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "List scan attempts for an event",
            description = "Returns every scan attempt for the named event in the [from, to] window, " +
                          "newest first. The caller must OWN the event (organizerUuid match).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Page of scan attempts",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event scan attempts retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "9b1f3c2e-6a47-4f7c-9d2b-1d6f0a1e5b91",
                                            "attemptedAt": "2026-06-19T19:42:11Z",
                                            "outcome": "ALLOWED",
                                            "ticketNumber": "20260619-48291X",
                                            "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                            "bookingId": "8d2c3e4a-1f5b-46a7-8c9d-0e1f2a3b4c5d",
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "scannerEmail": "tariro@harare-arena.co.zw",
                                            "scannerDisplayName": "Tariro Chikomo",
                                            "scannerUserUuid": "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 1827,
                                        "totalPages": 92
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Bad range or page params",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "'size' must be between 1 and 100.", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller does not own this event (or is not EVENT_ORGANIZER)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You do not own this event", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Event not found in event-service",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<PageResponse<ScanAttemptDTO>>> eventScans(
            Authentication authentication,
            @PathVariable UUID eventId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID organizerUuid = requireOrganizer(authentication);
        validateRange(from, to);
        validatePage(page, size);
        requireEventOwnership(eventId, organizerUuid);
        log.debug("GET /scans/events/{} organizer={} from={} to={} page={} size={}",
                eventId, organizerUuid, from, to, page, size);
        return ResponseEntity.ok(ApiResult.ok("Event scan attempts retrieved",
                scanReportService.listEventScans(eventId, from, to, page, size)));
    }

    @GetMapping("/events/{eventId}/stats")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Outcome stats for an event",
            description = "Outcome breakdown for the named event over the [from, to] window. " +
                          "Every Outcome enum value is present in the response (zero-filled).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Stats computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event scan stats retrieved",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "from": "2026-06-19T17:00:00Z",
                                        "to": "2026-06-20T02:00:00Z",
                                        "total": 1827,
                                        "byOutcome": {
                                          "ALLOWED": 1742,
                                          "ALREADY_REDEEMED": 63,
                                          "WRONG_ORGANIZER": 4,
                                          "NOT_ASSIGNED_TO_EVENT": 1,
                                          "TICKET_NOT_FOUND": 17,
                                          "BOOKING_NOT_CONFIRMED": 0
                                        }
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "'from' after 'to'",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-30T00:00:00Z) must not be after 'to' (2026-06-01T00:00:00Z).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller does not own this event",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You do not own this event", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Event not found in event-service",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<EventScanStatsDTO>> eventStats(
            Authentication authentication,
            @PathVariable UUID eventId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        UUID organizerUuid = requireOrganizer(authentication);
        validateRange(from, to);
        requireEventOwnership(eventId, organizerUuid);
        log.debug("GET /scans/events/{}/stats organizer={} from={} to={}",
                eventId, organizerUuid, from, to);
        return ResponseEntity.ok(ApiResult.ok("Event scan stats retrieved",
                scanReportService.eventStats(eventId, from, to)));
    }

    @GetMapping("/team-stats")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Team scan-outcome leaderboard",
            description = "Per-team-member outcome breakdown for the calling organizer's gate staff " +
                          "over the [from, to] window. Members are ordered by total scans DESC.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Leaderboard computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team scan stats retrieved",
                                      "data": {
                                        "from": "2026-06-19T17:00:00Z",
                                        "to": "2026-06-20T02:00:00Z",
                                        "members": [
                                          {
                                            "scannerUserUuid": "7e9a1c2b-4d5f-46a7-89b0-1c2d3e4f5a6b",
                                            "scannerEmail": "tariro@harare-arena.co.zw",
                                            "scannerDisplayName": "Tariro Chikomo",
                                            "total": 412,
                                            "byOutcome": {
                                              "ALLOWED": 380,
                                              "ALREADY_REDEEMED": 24,
                                              "WRONG_ORGANIZER": 2,
                                              "NOT_ASSIGNED_TO_EVENT": 0,
                                              "TICKET_NOT_FOUND": 6,
                                              "BOOKING_NOT_CONFIRMED": 0
                                            }
                                          },
                                          {
                                            "scannerUserUuid": "1c2d3e4f-5a6b-47c8-89d0-2e3f4a5b6c7d",
                                            "scannerEmail": "rufaro@harare-arena.co.zw",
                                            "scannerDisplayName": "Rufaro Moyo",
                                            "total": 287,
                                            "byOutcome": {
                                              "ALLOWED": 270,
                                              "ALREADY_REDEEMED": 12,
                                              "WRONG_ORGANIZER": 1,
                                              "NOT_ASSIGNED_TO_EVENT": 0,
                                              "TICKET_NOT_FOUND": 4,
                                              "BOOKING_NOT_CONFIRMED": 0
                                            }
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "'from' after 'to'",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-30T00:00:00Z) must not be after 'to' (2026-06-01T00:00:00Z).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<TeamStatsResponseDTO>> teamStats(
            Authentication authentication,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        UUID organizerUuid = requireOrganizer(authentication);
        validateRange(from, to);
        log.debug("GET /scans/team-stats organizer={} from={} to={}", organizerUuid, from, to);
        return ResponseEntity.ok(ApiResult.ok("Team scan stats retrieved",
                scanReportService.teamStats(organizerUuid, from, to)));
    }

    // -----------------------------------------------------------------
    // Validation + identity helpers — small enough to keep in-controller.
    // -----------------------------------------------------------------

    private static void validateRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new BadRequestException("'from' and 'to' are required.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' (" + from + ") must not be after 'to' (" + to + ").");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("'page' must be >= 0.");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("'size' must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    /** Every EVENT_ORGANIZER / TEAM_MEMBER token minted post-V20 carries the
     *  userUuid claim. A legacy token without it can't be scoped to a specific
     *  scanner — fail closed with a clear 400 so the FE re-prompts a login. */
    private static UUID requireScannerUserUuid(Authentication authentication) {
        UUID userUuid = AuthenticatedCaller.userUuid(authentication);
        if (userUuid == null) {
            throw new BadRequestException(
                    "Your session is missing user identity. Please sign out and log in again.");
        }
        return userUuid;
    }

    /** Mirror of {@link OrganizerReportController#requireOrganizer}. A legacy
     *  EVENT_ORGANIZER token without the organizerUuid claim must NOT silently
     *  scope to null and return another organizer's (or empty) data. */
    private static UUID requireOrganizer(Authentication authentication) {
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        if (organizerUuid == null) {
            throw new BadRequestException(
                    "Your session is missing organizer identity. Please sign out and log in again.");
        }
        return organizerUuid;
    }

    /**
     * Resolve the human display name for the calling scanner. Mirrors the
     * fall-back ladder in {@link TicketScanController#resolveScannerDisplayName}
     * so the value shown in the /me/stats response matches the value that
     * lands in {@code booking_items.redeemed_by_name} when the same user scans.
     */
    private static String resolveDisplayName(Authentication authentication) {
        if (authentication == null) return null;
        if (authentication.getDetails() instanceof JwtAuthDetails d) {
            String first = d.firstName();
            String last = d.lastName();
            boolean hasFirst = first != null && !first.isBlank();
            boolean hasLast = last != null && !last.isBlank();
            if (hasFirst && hasLast) return first + " " + last;
            if (hasFirst) return first;
            if (hasLast) return last;
        }
        return authentication.getName();
    }

    /**
     * Per-event endpoints must reject a caller who doesn't own the event,
     * even if they have EVENT_ORGANIZER role globally. Same authorization
     * model as {@code BookingService.requireEventOwnership}, kept local to
     * the controller so this surface doesn't have to reach into BookingService.
     *
     * <p>Failure modes:
     * <ul>
     *   <li>EventServiceClient unavailable -> 403 (can't verify ownership,
     *       fail closed — same rule the BookingService uses).</li>
     *   <li>Event not found -> 404 (the FE knows the eventId is stale).</li>
     *   <li>Owner uuid doesn't match -> 403 with an explicit message.</li>
     * </ul>
     */
    private void requireEventOwnership(UUID eventId, UUID requesterOrganizerUuid) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing scan reports for eventId={}", eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        EventLookupDTO data;
        try {
            // Internal variant — the public endpoint strips tenantUserUuid for
            // anonymous (= server-side) callers, which made this check always
            // see null and 403 every organizer.
            ApiResult<EventLookupDTO> lookup = client.getEventInternal(eventId, eventInternalToken);
            data = lookup == null ? null : lookup.getData();
        } catch (Exception e) {
            log.warn("Event ownership lookup failed eventId={} cause={}", eventId, e.toString());
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        if (data == null) {
            throw new NotFoundException("Event not found");
        }
        UUID ownerTenantUserUuid = data.getTenantUserUuid();
        if (ownerTenantUserUuid == null || !ownerTenantUserUuid.equals(requesterOrganizerUuid)) {
            log.warn("Event ownership check failed eventId={} requesterOrganizerUuid={} ownerTenantUserUuid={}",
                    eventId, requesterOrganizerUuid, ownerTenantUserUuid);
            throw new AccessDeniedException("You do not own this event");
        }
    }
}
