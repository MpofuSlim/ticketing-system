package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.security.JwtFilter;
import com.innbucks.eventservice.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
@Validated  // makes @Min/@Max on @RequestParam fire on pagination params (caps DoS via size=999999)
@Tag(name = "Events", description = "Browse events publicly; tenants can create/update/delete events.")
public class EventController {

    private final EventService eventService;
    private final com.innbucks.eventservice.client.UserUuidLookupGateway userUuidLookupGateway;

    /**
     * Upper bound on @RequestParam {@code size} across every listing endpoint.
     * Per-row enrichment ({@code enrichWithAvailability} fans out to
     * booking-service + user-service for organizer details), so cost-per-page
     * is non-trivial — an unbounded {@code size=999999} would pin a worker
     * thread and burn sibling-service capacity past what the gateway's
     * 50 rps / 100 burst rate-limit caps (the limiter caps frequency, not
     * cost-per-request). 100 is generous for normal list-and-scroll UX,
     * a reject for everyone else.
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Shared secret booking-service must present on the internal
     * PATCH /events/{id}/availability/consume call. Defense-in-depth
     * on top of the gateway's event-availability-deny rule — even if a
     * future routing mistake or pod-network breach makes the path
     * reachable, the controller still demands the token.
     */
    @Value("${innbucks.internal-api-token}")
    private String expectedInternalToken;

    @GetMapping
    @SecurityRequirements()
    @Operation(
            summary = "List all (active and inactive) events",
            description = """
                    Returns a paginated list of **non-deleted** events, including
                    both `active=true` and `active=false`. Use `GET /events/active`
                    to fetch only the active ones.

                    Filtering:
                    - **from/to** are interpreted as calendar dates (`yyyy-MM-dd`).
                    - Internally they map to `[from at start-of-day .. to at end-of-day]` using the server's local timezone.
                    - **venue** matches case-insensitive substring (`LIKE %venue%`).
                    Sorting uses the persisted field name `startDateTime` by default (internal storage is `LocalDateTime`).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of events",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Events page", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": [
                                              {
                                                "name": "VIP",
                                                "description": "Front rows",
                                                "categoryPrice": 100.00,
                                                "sections": [
                                                  { "section": "A", "seatCount": 25, "price": 100.00 },
                                                  { "section": "B", "seatCount": 25, "price": 100.00 }
                                                ]
                                              }
                                            ]
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getAllEvents(
            Authentication authentication,
            @Parameter(description = "Inclusive lower bound date for events (maps to start of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive upper bound date for events (maps to end of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Venue substring filter (case-insensitive)")
            @RequestParam(required = false) String venue,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        Page<EventResponseDTO> result;
        if (isOrganizerOnly(authentication)) {
            UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                    .organizerUuid(authentication);
            log.debug("Listing events (organizer scope) organizerUuid={} from={} to={} venue={} page={} size={} sortBy={}",
                    organizerUuid, from, to, venue, page, size, sortBy);
            result = eventService.getMyEvents(organizerUuid, fromDateTime, toDateTime, venue, page, size, sortBy);
        } else {
            log.debug("Listing events (public scope) from={} to={} venue={} page={} size={} sortBy={}",
                    from, to, venue, page, size, sortBy);
            result = eventService.getAllActiveEvents(fromDateTime, toDateTime, venue, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }


    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER','SUPER_ADMIN')")
    @Operation(
            summary = "List the authenticated organizer's own events",
            description = """
                    Returns a paginated list of **non-deleted** events owned by the
                    authenticated principal (matched on the caller's `organizerUuid`
                    JWT claim against each event's `tenantUserUuid`). Includes both
                    active=true and active=false events.

                    Filters and sorting follow the same rules as `GET /events`.

                    Use this instead of `GET /events` when the caller wants to see
                    only their own events. SUPER_ADMIN can also call this — they
                    will see only events they personally created (their own
                    `organizerUuid`), since this endpoint is scoped by the
                    principal. To see *every* organizer's events, SUPER_ADMIN
                    should call `GET /events`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of the caller's events"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but lacks the required role")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getMyEvents(
            Authentication authentication,
            @Parameter(description = "Inclusive lower bound date for events (maps to start of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive upper bound date for events (maps to end of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Venue substring filter (case-insensitive)")
            @RequestParam(required = false) String venue,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        // Scope by the stable organizerUuid JWT claim — the right scope for
        // both EVENT_ORGANIZERs (their own uuid) and TEAM_MEMBERs (their
        // parent organizer's uuid). A missing claim (legacy pre-V20 token)
        // gets an empty page — re-issuing the token via /auth/refresh fixes
        // it, safer than the previous email fallback.
        UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        boolean isTeamMember = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TEAM_MEMBER".equals(a.getAuthority()));
        log.info("GET /events/my organizerUuid={} teamMember={} from={} to={} venue={} page={} size={}",
                organizerUuid, isTeamMember, from, to, venue, page, size);
        if (organizerUuid == null) {
            return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully",
                    Page.empty(org.springframework.data.domain.PageRequest.of(page, size))));
        }
        Page<EventResponseDTO> result;
        if (isTeamMember) {
            // Deny-by-default: a TEAM_MEMBER sees ONLY the events their
            // organizer has explicitly assigned to them (keyed on the team
            // member's OWN userUuid; organizerUuid filters as defence-in-
            // depth). Fetched per-request so assignment changes take effect
            // immediately (no re-login required). On user-service outage the
            // gateway returns an empty list, which means no events — safer
            // than leaking the organizer-wide set.
            UUID teamMemberUuid = com.innbucks.eventservice.security
                    .AuthenticatedCaller.userUuid(authentication);
            java.util.List<UUID> assigned = teamMemberUuid == null
                    ? java.util.List.of()
                    : userUuidLookupGateway.assignedEventIdsFor(teamMemberUuid);
            result = eventService.getMyAssignedEvents(
                    organizerUuid, assigned, fromDateTime, toDateTime, venue, page, size, sortBy);
        } else {
            // EVENT_ORGANIZER (or SUPER_ADMIN-as-organizer): full organizer-wide view.
            result = eventService.getMyEvents(
                    organizerUuid, fromDateTime, toDateTime, venue, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @GetMapping("/by-organizer")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List a specific organizer's events (SUPER_ADMIN only)",
            description = """
                    Returns a paginated list of **non-deleted** events owned by the
                    supplied `organizerUuid` (the organizer's stable cross-service id —
                    the same uuid carried in the `tenantUserUuid` field on every event),
                    including both `active=true` and `active=false` events.

                    Restricted to **SUPER_ADMIN**. This is the platform-wide tool for
                    inspecting *any* organizer's events by id — unlike `GET /events/my`,
                    which is always scoped to the caller's own JWT.

                    Filters and sorting follow the same rules as `GET /events`:
                    - **from/to** are calendar dates (`yyyy-MM-dd`), mapped to
                      `[from start-of-day .. to end-of-day]`.
                    - **venue** matches a case-insensitive substring.

                    An `organizerUuid` with no events (or an unknown one) returns **200**
                    with an empty `content` array — not a 404.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of the organizer's events (may be empty)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Organizer events page", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": []
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Missing organizerUuid",
                    content = @Content(schema = @Schema(example = "{\"code\":\"400 BAD_REQUEST\",\"message\":\"organizerUuid is required\",\"data\":null}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but lacks SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getEventsByOrganizer(
            @Parameter(description = "Owning organizer's user_uuid whose events to list", required = true)
            @RequestParam UUID organizerUuid,

            @Parameter(description = "Inclusive lower bound date for events (maps to start of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive upper bound date for events (maps to end of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Venue substring filter (case-insensitive)")
            @RequestParam(required = false) String venue,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        if (organizerUuid == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "organizerUuid is required"));
        }
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        log.info("GET /events/by-organizer organizerUuid={} from={} to={} venue={} page={} size={}",
                organizerUuid, from, to, venue, page, size);
        // Reuses the same organizer-scoped query as GET /events/my, but with
        // an explicitly supplied organizerUuid instead of the caller's own —
        // gated to SUPER_ADMIN so only platform admins can list another
        // organizer's events.
        Page<EventResponseDTO> result = eventService.getMyEvents(
                organizerUuid, fromDateTime, toDateTime, venue, page, size, sortBy);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @GetMapping("/active")
    @SecurityRequirements()
    @Operation(
            summary = "List events that are flagged active",
            description = """
                    Returns a paginated list of **non-deleted** events whose `active`
                    flag is `true`. Tenants flip `active` to `false` when an event ends
                    (or it is flipped by a future scheduler), so this endpoint excludes
                    those while `GET /events` still includes them.

                    Filtering and sorting follow the same rules as `GET /events`, plus:
                    - **country** — exact match, case-insensitive (e.g. `Zimbabwe`).
                    - **category** — one of BOOKS, COMEDY, HALF_MARATHON, MARATHON, CONCERT, SPORT.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of active events",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Active events page", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "rejected": false,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": [
                                              {
                                                "name": "VIP",
                                                "description": "Front rows",
                                                "categoryPrice": 100.00,
                                                "sections": [
                                                  { "section": "A", "seatCount": 25, "price": 100.00 }
                                                ]
                                              }
                                            ]
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getActiveEvents(
            Authentication authentication,
            @Parameter(description = "Inclusive lower bound date for events (maps to start of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive upper bound date for events (maps to end of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Venue substring filter (case-insensitive)")
            @RequestParam(required = false) String venue,

            @Parameter(description = "Country filter (exact match, case-insensitive), e.g. Zimbabwe")
            @RequestParam(required = false) String country,

            @Parameter(description = "Category filter (BOOKS, COMEDY, HALF_MARATHON, MARATHON, CONCERT, SPORT)")
            @RequestParam(required = false) EventCategory category,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        Page<EventResponseDTO> result;
        if (isOrganizerOnly(authentication)) {
            UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                    .organizerUuid(authentication);
            log.debug("Listing active events (organizer scope) organizerUuid={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                    organizerUuid, from, to, venue, country, category, page, size, sortBy);
            result = eventService.getMyActiveEvents(organizerUuid, fromDateTime, toDateTime, venue, country, category, page, size, sortBy);
        } else {
            log.debug("Listing active events (public scope) from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                    from, to, venue, country, category, page, size, sortBy);
            result = eventService.getActiveOnlyEvents(fromDateTime, toDateTime, venue, country, category, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Active events retrieved successfully", result));
    }

    @GetMapping("/inactive")
    @SecurityRequirements()
    @Operation(
            summary = "List events that are flagged inactive",
            description = """
                    Returns a paginated list of **non-deleted** events whose `active`
                    flag is `false` — the mirror of `GET /events/active`. This is the
                    set an organizer/admin works from: events not yet published,
                    events deactivated after they ended, and events an admin has
                    **rejected** (rejected events are always `active=false`, so they
                    surface here for an admin to review and approve).

                    Unlike `/events/active`, this listing intentionally **includes
                    events whose end-time has already passed** (no upcoming-only
                    cutoff), since "inactive" naturally covers finished events.

                    Scope:
                    - An authenticated **EVENT_ORGANIZER** sees only their own inactive events.
                    - **SUPER_ADMIN** and anonymous/customer callers see every tenant's.

                    Filtering and sorting follow the same rules as `GET /events/active`
                    (from/to/venue/country/category).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of inactive events",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Inactive events page", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Inactive events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Winter Gala (draft)",
                                            "description": "Not yet published by the organizer.",
                                            "venue": "Rainbow Towers",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8311, "longitude": 31.0468 },
                                            "bannerUrl": null,
                                            "startDateTime": "2026-08-20T19:00:00", "endDateTime": "2026-08-20T23:00:00",
                                            "totalCapacity": 800,
                                            "availableTickets": 800,
                                            "active": false,
                                            "rejected": false,
                                            "createdAt": "2026-06-01T09:00:00",
                                            "updatedAt": "2026-06-01T09:00:00",
                                            "seatCategories": []
                                          },
                                          {
                                            "eventId": "9b2ffff0-3d0a-4b1e-8a2e-2f9b0c5d1a44",
                                            "eventNo": null,
                                            "tenantUserUuid": "5fc4c9d2-7b4f-4d12-a1c3-9e2f0bda1d3e",
                                            "title": "Unverified Pop-Up",
                                            "description": "Rejected by an administrator during review.",
                                            "venue": "Unknown Hall",
                                            "country": "Zimbabwe", "category": "COMEDY",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": null,
                                            "startDateTime": "2026-07-10T18:00:00", "endDateTime": "2026-07-10T20:00:00",
                                            "totalCapacity": 150,
                                            "availableTickets": 150,
                                            "active": false,
                                            "rejected": true,
                                            "createdAt": "2026-06-02T11:00:00",
                                            "updatedAt": "2026-06-03T08:30:00",
                                            "seatCategories": []
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 2,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getInactiveEvents(
            Authentication authentication,
            @Parameter(description = "Inclusive lower bound date for events (maps to start of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive upper bound date for events (maps to end of day)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Venue substring filter (case-insensitive)")
            @RequestParam(required = false) String venue,

            @Parameter(description = "Country filter (exact match, case-insensitive), e.g. Zimbabwe")
            @RequestParam(required = false) String country,

            @Parameter(description = "Category filter (BOOKS, COMEDY, HALF_MARATHON, MARATHON, CONCERT, SPORT)")
            @RequestParam(required = false) EventCategory category,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        Page<EventResponseDTO> result;
        if (isOrganizerOnly(authentication)) {
            UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                    .organizerUuid(authentication);
            log.debug("Listing inactive events (organizer scope) organizerUuid={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                    organizerUuid, from, to, venue, country, category, page, size, sortBy);
            result = eventService.getMyInactiveEvents(organizerUuid, fromDateTime, toDateTime, venue, country, category, page, size, sortBy);
        } else {
            log.debug("Listing inactive events (public scope) from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                    from, to, venue, country, category, page, size, sortBy);
            result = eventService.getInactiveOnlyEvents(fromDateTime, toDateTime, venue, country, category, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Inactive events retrieved successfully", result));
    }


    @GetMapping("/{id}")
    @SecurityRequirements()
    @Operation(
            summary = "Get event by id",
            description = """
                    Returns a single **non-deleted** event.

                    Errors:
                    - **400** if the event does not exist or is soft-deleted (handled by the global exception handler today).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Event details",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event details", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event retrieved successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "eventNo": null,
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 420,
                                        "active": true,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T15:00:00",
                                        "seatCategories": [
                                          {
                                            "name": "VIP",
                                            "description": "Front rows",
                                            "categoryPrice": 100.00,
                                            "sections": [
                                              { "section": "A", "seatCount": 25, "price": 100.00 },
                                              { "section": "B", "seatCount": 25, "price": 100.00 }
                                            ]
                                          },
                                          {
                                            "name": "GA",
                                            "description": "General admission",
                                            "categoryPrice": 60.00,
                                            "sections": [
                                              { "section": "F", "seatCount": 50, "price": 60.00 }
                                            ]
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"error\":\"Event not found\"}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> getEventById(
            @Parameter(description = "Event UUID") @PathVariable UUID id
    ) {
        log.debug("Lookup event by id eventId={}", id);
        return ResponseEntity.ok(ApiResult.ok("Event retrieved successfully", eventService.getEventById(id)));
    }

    @GetMapping("/by-country")
    @SecurityRequirements()
    @Operation(
            summary = "List active events by country",
            description = """
                    Returns a paginated list of non-deleted events for a specific country,
                    ordered by `startDateTime` ascending so the soonest-starting event is first.
                    Country match is case-insensitive.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of events for the country, earliest first",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Events by country", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": 1,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": []
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getEventsByCountry(
            @Parameter(description = "Country name, e.g. Zimbabwe (case-insensitive)")
            @RequestParam String country,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        log.debug("GET /events/by-country country={} page={} size={}", country, page, size);
        Page<EventResponseDTO> result = eventService.getEventsByCountry(country, page, size);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @GetMapping("/search")
    @SecurityRequirements()
    @Operation(
            summary = "Search events by keyword",
            description = """
                    Free-text search across `title`, `description`, and `venue`.
                    Case-insensitive substring match — typing `H` returns every
                    event with `H` anywhere in those fields; typing `Harare`
                    narrows to events whose title, description, or venue mentions
                    Harare. Powers the customer-facing search bar.

                    Only returns **active**, non-deleted events (the same set
                    surfaced by `GET /events/active`). Empty result sets return
                    a 200 with an empty `content` array — the frontend should
                    show a "No results" state, not treat it as an error.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of events matching the keyword (may be empty)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Search results", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "CONCERT",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": []
                                          }
                                        ],
                                        "pageable": {
                                          "pageNumber": 0,
                                          "pageSize": 10,
                                          "sort": { "sorted": true, "unsorted": false, "empty": false },
                                          "offset": 0,
                                          "paged": true,
                                          "unpaged": false
                                        },
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "last": true,
                                        "first": true,
                                        "size": 10,
                                        "number": 0,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Missing or blank `q` parameter")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> searchEvents(
            @Parameter(description = "Search keyword — matches title, description, or venue (case-insensitive substring)")
            @RequestParam("q") String q,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Search keyword 'q' is required"));
        }
        log.debug("GET /events/search q={} page={} size={} sortBy={}", q, page, size, sortBy);
        Page<EventResponseDTO> result = eventService.searchEvents(q, page, size, sortBy);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Create event",
            description = """
                    Creates a new event for the authenticated **EVENT_ORGANIZER** or **ADMIN**.

                    The authenticated principal's `organizerUuid` JWT claim becomes the owning
                    `tenantUserUuid`, and the event's `country` is taken from the caller's JWT
                    `country` claim — neither is part of the request body.

                    The request is `multipart/form-data` with two parts:
                    - `event` — JSON body matching `CreateEventRequest` (title, description, venue, category, location, startDateTime, endDateTime, totalCapacity).
                    - `eventBanner` — optional image file (JPEG/PNG/GIF/WEBP, max 5 MB).

                    Validation:
                    - `startDateTime` and `endDateTime` must be **in the future**, and `endDateTime` must be **after** `startDateTime`.
                    - `category` is required (one of BOOKS, COMEDY, HALF_MARATHON, MARATHON, CONCERT, SPORT).
                    - `location.latitude` ∈ [-90, 90]; `location.longitude` ∈ [-180, 180].
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = CreateEventMultipartRequest.class),
                            encoding = {
                                    @Encoding(name = "event", contentType = MediaType.APPLICATION_JSON_VALUE),
                                    @Encoding(name = "eventBanner", contentType = "image/png, image/jpeg, image/gif, image/webp")
                            }
                    )
            )
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Event created successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "eventNo": null,
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": false,
                                        "createdAt": "2026-05-02T15:00:00",
                                        "updatedAt": "2026-05-02T15:00:00",
                                        "seatCategories": []
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER"),
            @ApiResponse(responseCode = "422", description = "Validation errors",
                    content = @Content(schema = @Schema(example = "{\"title\":\"Title is required\"}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> createEvent(
            @Valid @RequestPart("event") CreateEventRequestDTO request,
            @RequestPart(value = "eventBanner", required = false) MultipartFile eventBanner,
            @RequestAttribute(name = JwtFilter.COUNTRY_ATTRIBUTE, required = false) String country,
            Authentication authentication
    ) {
        // For an EVENT_ORGANIZER, organizerUuid equals their own userUuid —
        // the JWT mint path in user-service computes it that way. This is the
        // sole owning-organizer pointer now (the legacy email-as-tenantId
        // column was deprecated in V7).
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        log.info("Creating event tenantUserUuid={} country={} title={} venue={} hasBanner={}",
                tenantUserUuid, country, request.getTitle(), request.getVenue(),
                eventBanner != null && !eventBanner.isEmpty());
        EventResponseDTO created = eventService.createEvent(tenantUserUuid, country, request, eventBanner);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Event created successfully", created));
    }

    @GetMapping("/{id}/banner")
    @SecurityRequirements()
    @Operation(
            summary = "Get event banner image",
            description = "Returns the raw bytes of the event banner with its original Content-Type."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banner image bytes"),
            @ApiResponse(responseCode = "400", description = "Event not found or no banner uploaded")
    })
    public ResponseEntity<byte[]> getEventBanner(
            @Parameter(description = "Event UUID") @PathVariable UUID id
    ) {
        EventService.BannerImage banner = eventService.getEventBanner(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(banner.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
                .body(banner.bytes());
    }

    // Schema-only helper so springdoc renders a usable multipart form in
    // Swagger UI (separate JSON text field + file picker). Not used at runtime.
    @Schema(name = "CreateEventMultipartRequest")
    @SuppressWarnings("unused")
    private static class CreateEventMultipartRequest {
        @Schema(description = "Event JSON payload", implementation = CreateEventRequestDTO.class)
        public CreateEventRequestDTO event;

        @Schema(type = "string", format = "binary", description = "Optional banner image (JPEG/PNG/GIF/WEBP, max 5 MB).")
        public MultipartFile eventBanner;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Update event",
            description = """
                    Updates an existing event.

                    Authorization:
                    - Requires **EVENT_ORGANIZER**.
                    - EVENT_ORGANIZER can update only their own event. .

                    Behavior:
                    - Fields omitted from the body remain unchanged (partial update).
                    - If `totalCapacity` changes, `availableTickets` is adjusted by the capacity difference.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Updated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event updated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event updated successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "eventNo": null,
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert (Updated)",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 600,
                                        "availableTickets": 520,
                                        "active": true,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T16:00:00",
                                        "seatCategories": [
                                          {
                                            "name": "VIP",
                                            "description": "Front rows",
                                            "categoryPrice": 100.00,
                                            "sections": [
                                              { "section": "A", "seatCount": 25, "price": 100.00 }
                                            ]
                                          }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Not found or not authorized",
                    content = @Content(schema = @Schema(example = "{\"error\":\"You are not authorized to update this event\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> updateEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateEventRequestDTO request,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Updating event eventId={} tenantUserUuid={} request={}", id, tenantUserUuid, request);
        EventResponseDTO updated = eventService.updateEvent(tenantUserUuid, role, id, request);
        return ResponseEntity.ok(ApiResult.ok("Event updated successfully", updated));
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Activate event",
            description = """
                    Flips the event's `active` flag to `true`.

                    Newly created events start with `active=false`; the owning
                    **EVENT_ORGANIZER** calls this endpoint once the event
                    is ready to be surfaced via `GET /events/active` and
                    `GET /events/by-country`.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Activated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event activated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event activated successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "eventNo": null,
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": true,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T16:00:00",
                                        "seatCategories": []
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Not found or not authorized",
                    content = @Content(schema = @Schema(example = "{\"error\":\"You are not authorized to activate this event\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> activateEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Activating event eventId={} tenantUserUuid={}", id, tenantUserUuid);
        EventResponseDTO activated = eventService.activateEvent(tenantUserUuid, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event activated successfully", activated));
    }

    @PutMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Deactivate event",
            description = """
                    Flips the event's `active` flag to `false` — the mirror of
                    `PUT /events/{id}/activate`. The event drops out of
                    `GET /events/active`, `GET /events/search` and
                    `GET /events/by-country`, and reappears under
                    `GET /events/inactive`.

                    Authorization:
                    - Requires **EVENT_ORGANIZER** or **SUPER_ADMIN**.
                    - An EVENT_ORGANIZER can deactivate only their own event; a
                      SUPER_ADMIN can deactivate any.

                    This is the organizer's own "unpublish / take it down" action.
                    It does **not** set the admin `rejected` flag — use
                    `PUT /events/{id}/reject` for moderation.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Deactivated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event deactivated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event deactivated successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "eventNo": null,
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": false,
                                        "rejected": false,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T16:30:00",
                                        "seatCategories": []
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Not the owner (and not SUPER_ADMIN)",
                    content = @Content(schema = @Schema(example = "{\"code\":\"403 FORBIDDEN\",\"message\":\"You are not authorized to deactivate this event\",\"data\":null}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Event not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> deactivateEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Deactivating event eventId={} tenantUserUuid={}", id, tenantUserUuid);
        EventResponseDTO deactivated = eventService.deactivateEvent(tenantUserUuid, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event deactivated successfully", deactivated));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Reject event (admin moderation)",
            description = """
                    **SUPER_ADMIN only.** Marks the event as `rejected=true` and
                    forces `active=false`, removing it from every public bookable
                    listing (`/events/active`, `/events/search`,
                    `/events/by-country`). The event remains visible under
                    `GET /events/inactive` so an admin can later approve it.

                    A rejected event **cannot be re-activated** by its organizer —
                    `PUT /events/{id}/activate` returns 409 until an admin clears
                    the flag via `PUT /events/{id}/approve`.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Rejected",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event rejected", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event rejected successfully",
                                      "data": {
                                        "eventId": "9b2ffff0-3d0a-4b1e-8a2e-2f9b0c5d1a44",
                                        "eventNo": null,
                                        "tenantUserUuid": "5fc4c9d2-7b4f-4d12-a1c3-9e2f0bda1d3e",
                                        "title": "Unverified Pop-Up",
                                        "description": "Rejected by an administrator during review.",
                                        "venue": "Unknown Hall",
                                        "country": "Zimbabwe", "category": "COMEDY",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": null,
                                        "startDateTime": "2026-07-10T18:00:00", "endDateTime": "2026-07-10T20:00:00",
                                        "totalCapacity": 150,
                                        "availableTickets": 150,
                                        "active": false,
                                        "rejected": true,
                                        "createdAt": "2026-06-02T11:00:00",
                                        "updatedAt": "2026-06-03T08:30:00",
                                        "seatCategories": []
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Event not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> rejectEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        log.info("Rejecting event eventId={} admin={}", id, authentication.getName());
        EventResponseDTO rejected = eventService.rejectEvent(id);
        return ResponseEntity.ok(ApiResult.ok("Event rejected successfully", rejected));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Approve (un-reject) event (admin moderation)",
            description = """
                    **SUPER_ADMIN only.** Clears a previous rejection
                    (`rejected=false`). The event stays `active=false` — the owning
                    organizer re-publishes it with `PUT /events/{id}/activate` when
                    ready (now permitted again because the rejection is cleared).

                    Calling approve on an event that was never rejected is a no-op
                    that returns the event unchanged (still `rejected=false`).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Approved (rejection cleared)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Event approved", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event approved successfully",
                                      "data": {
                                        "eventId": "9b2ffff0-3d0a-4b1e-8a2e-2f9b0c5d1a44",
                                        "eventNo": null,
                                        "tenantUserUuid": "5fc4c9d2-7b4f-4d12-a1c3-9e2f0bda1d3e",
                                        "title": "Unverified Pop-Up",
                                        "description": "Cleared after a second review.",
                                        "venue": "Unknown Hall",
                                        "country": "Zimbabwe", "category": "COMEDY",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": null,
                                        "startDateTime": "2026-07-10T18:00:00", "endDateTime": "2026-07-10T20:00:00",
                                        "totalCapacity": 150,
                                        "availableTickets": 150,
                                        "active": false,
                                        "rejected": false,
                                        "createdAt": "2026-06-02T11:00:00",
                                        "updatedAt": "2026-06-04T07:15:00",
                                        "seatCategories": []
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Event not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> approveEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        log.info("Approving event eventId={} admin={}", id, authentication.getName());
        EventResponseDTO approved = eventService.approveEvent(id);
        return ResponseEntity.ok(ApiResult.ok("Event approved successfully", approved));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Delete event (soft delete)",
            description = """
                    Soft-deletes an event (`deleted=true`).

                    Authorization:
                    - Requires **EVENT_ORGANIZER**.
                    - EVENT_ORGANIZER can delete only their own event. .
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Deleted",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Event deleted", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event deleted successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Not found or not authorized",
                    content = @Content(schema = @Schema(example = "{\"error\":\"You are not authorized to delete this event\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<Void>> deleteEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Deleting event eventId={} tenantUserUuid={}", id, tenantUserUuid);
        eventService.deleteEvent(tenantUserUuid, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event deleted successfully", null));
    }

    @PatchMapping("/{id}/availability/consume")
    @SecurityRequirements()
    @Operation(
            summary = "Consume availability (internal)",
            description = """
                    Internal endpoint: booking-service calls this when a booking is
                    confirmed so the event's stored `availableTickets` decrements
                    atomically. Returns the new value. Refuses to underflow — a
                    request that would push availability below zero is rejected.

                    Requires the `X-Internal-Token` shared secret. Missing or
                    wrong token returns 401. The gateway also blocks this path
                    from the public internet via the event-availability-deny
                    rule; this controller check is defence in depth.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Availability decremented",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Availability consumed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Availability consumed",
                                      "data": { "availableTickets": 7 }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "count missing/non-positive or insufficient availability"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token")
    })
    public ResponseEntity<ApiResult<AvailabilityResponseDTO>> consumeAvailability(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestParam("count") int count,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized PATCH /events/{}/availability/consume — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        log.info("PATCH /events/{}/availability/consume count={}", id, count);
        int remaining = eventService.consumeAvailability(id, count);
        return ResponseEntity.ok(ApiResult.ok("Availability consumed",
                new AvailabilityResponseDTO(remaining)));
    }

    @PatchMapping("/{id}/availability/release")
    @SecurityRequirements()
    @Operation(
            summary = "Release availability (internal)",
            description = """
                    Internal endpoint: booking-service calls this when a confirmed
                    booking is reversed (admin refund, no-show, or — once real
                    payment integration lands — a money-transfer failure that
                    arrives after the booking was already CONFIRMED) so the seats
                    it consumed return to the available pool. Returns the new
                    value. Refuses to overflow — a request that would push
                    availability above `totalCapacity` is rejected as a 400, so a
                    buggy caller or a replay can't inflate capacity.

                    Requires the `X-Internal-Token` shared secret. Missing or
                    wrong token returns 401. The gateway also blocks this path
                    from the public internet via the event-availability-deny
                    rule (which covers `/events/*/availability/**`, including
                    this path); this controller check is defence in depth.

                    Idempotency lives on the CALLER's side: booking-service
                    tracks per-booking `availability_released` so retrying the
                    reverse operation never double-credits.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Availability released",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Availability released", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Availability released",
                                      "data": { "availableTickets": 9 }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "count missing/non-positive, or releasing would exceed totalCapacity"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token")
    })
    public ResponseEntity<ApiResult<AvailabilityResponseDTO>> releaseAvailability(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestParam("count") int count,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized PATCH /events/{}/availability/release — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        log.info("PATCH /events/{}/availability/release count={}", id, count);
        int remaining = eventService.releaseAvailability(id, count);
        return ResponseEntity.ok(ApiResult.ok("Availability released",
                new AvailabilityResponseDTO(remaining)));
    }

    /**
     * Constant-time shared-secret check for the internal consume-availability
     * endpoint. Mirrors loyalty-service's InternalMerchantLookupController
     * pattern — {@code MessageDigest.isEqual} so an attacker can't derive the
     * token byte-by-byte from response-time differences.
     */
    private boolean authorizedInternal(String presented) {
        if (expectedInternalToken == null || expectedInternalToken.isBlank()) {
            log.warn("innbucks.internal-api-token is not configured; rejecting internal call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedInternalToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private String getCurrentRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .findFirst()
                .map(authority -> authority.getAuthority())
                .orElse("");
    }

    /**
     * True when the caller is authenticated as an EVENT_ORGANIZER but NOT a
     * SUPER_ADMIN. Used to auto-scope the broad listing endpoints so an
     * organizer never sees other organizers' events; SUPER_ADMIN keeps the
     * platform-wide view, and anonymous / customer callers also see all events.
     */
    private boolean isOrganizerOnly(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        boolean organizer = false;
        boolean superAdmin = false;
        for (var authority : authentication.getAuthorities()) {
            String a = authority.getAuthority();
            if ("ROLE_EVENT_ORGANIZER".equals(a)) organizer = true;
            else if ("ROLE_SUPER_ADMIN".equals(a)) superAdmin = true;
        }
        return organizer && !superAdmin;
    }
}
