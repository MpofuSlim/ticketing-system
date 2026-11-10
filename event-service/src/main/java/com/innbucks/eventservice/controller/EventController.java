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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
@Validated  // makes @Min/@Max on @RequestParam fire on pagination params (caps DoS via size=999999)
@Tag(name = "Events", description = "Browse events publicly; tenants can create/update/delete events.")
public class EventController {

    private final EventService eventService;

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

    @GetMapping("/categories")
    @SecurityRequirements()
    @Operation(
            summary = "List the event-category vocabulary",
            description = """
                    Every category an event can be filed under — the wire `code` (what
                    `POST/PUT /events` and the `?category=` filters accept, and what event
                    responses carry) plus the human `displayName` for dropdowns.

                    **Render pickers from this endpoint, never a hardcoded list** — values are
                    added over time and an unknown code on create/update/filter is a `400`.
                    Returned in curated display order, grouped (music → arts → faith → social →
                    business → expos → lifestyle → sports → OTHER) so a client can render
                    section headers in the order received. Public; cache per app-session.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The full vocabulary (64 values), in display order",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Categories (truncated)", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event categories retrieved successfully",
                                      "data": [
                                        { "code": "CONCERT", "displayName": "Concert" },
                                        { "code": "GOSPEL_CONCERT", "displayName": "Gospel Concert" },
                                        { "code": "MUSIC_FESTIVAL", "displayName": "Music Festival" },
                                        { "code": "MUSIC_GALA", "displayName": "Musical Gala" },
                                        { "code": "SUNGURA_SHOW", "displayName": "Sungura Show" },
                                        { "code": "OTHER", "displayName": "Other" }
                                      ]
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<java.util.List<EventCategoryOptionDTO>>> listCategories() {
        return ResponseEntity.ok(ApiResult.ok("Event categories retrieved successfully",
                java.util.Arrays.stream(EventCategory.values())
                        .map(EventCategoryOptionDTO::of)
                        .toList()));
    }

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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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
            // Everyone else, whatever their roles, gets the anonymous listing:
            // published events only, organizer id stripped.
            log.debug("Listing events (public scope) from={} to={} venue={} page={} size={} sortBy={}",
                    from, to, venue, page, size, sortBy);
            result = eventService.getAllActiveEvents(fromDateTime, toDateTime, venue, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }


    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List the authenticated organizer's own events",
            description = """
                    Returns a paginated list of **non-deleted** events owned by the
                    authenticated principal (matched on the caller's `organizerUuid`
                    JWT claim against each event's `tenantUserUuid`). Includes both
                    active=true and active=false events.

                    Filters and sorting follow the same rules as `GET /events`.

                    `salesStatus` (optional) keeps only the events whose `salesStatus` is that
                    value right now: `ON_SALE`, `NOT_YET_ON_SALE`, `SALES_CLOSED`, `ENDED` or
                    `NOT_PUBLISHED` (upper case). The filter runs on the server, so `totalElements`
                    and every page count only matching events. Leave it out for all of them.

                    Use this instead of `GET /events` when the caller wants to see
                    only their own events. SUPER_ADMIN can also call this — they
                    will see only events they personally created (their own
                    `organizerUuid`), since this endpoint is scoped by the
                    principal. To see *every* organizer's events, SUPER_ADMIN
                    should call `GET /events`.

                    Any other role is `403`. Gate staff are super-app scanners
                    (`/event-organizer/scanners`, `GET /events/gate`) and have no
                    organizer view; the scanner-app team members were retired on
                    2026-11-05.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of the caller's events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "The organizer's events", value = """
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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00+02:00", "endDateTime": "2026-06-15T22:00:00+02:00",
                                            "salesStartDateTime": null,
                                            "salesEndDateTime": "2026-06-15T18:00:00+02:00",
                                            "salesStatus": "ON_SALE",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "rejected": false,
                                            "reviewStatus": "APPROVED",
                                            "approvedAt": "2026-04-25T09:15:00+02:00",
                                            "createdAt": "2026-04-25T08:00:00+02:00",
                                            "updatedAt": "2026-05-02T15:00:00+02:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
                                          }
                                        ],
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "size": 10,
                                        "number": 0,
                                        "first": true,
                                        "last": true,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "No session", value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Neither EVENT_ORGANIZER nor SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Not an organizer", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)",
                                      "data": null
                                    }
                                    """)))
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

            @Parameter(description = "Only events with this sales status right now", example = "SALES_CLOSED")
            @RequestParam(required = false) com.innbucks.eventservice.dto.SalesStatus salesStatus,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "startDateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        // Scope by the stable organizerUuid JWT claim (an EVENT_ORGANIZER's
        // own uuid). A missing claim (legacy pre-V20 token) gets an empty
        // page — re-issuing the token via /auth/refresh fixes it, safer than
        // the previous email fallback.
        UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        log.info("GET /events/my organizerUuid={} from={} to={} venue={} salesStatus={} page={} size={}",
                organizerUuid, from, to, venue, salesStatus, page, size);
        if (organizerUuid == null) {
            return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully",
                    Page.empty(org.springframework.data.domain.PageRequest.of(page, size))));
        }
        // EVENT_ORGANIZER (or SUPER_ADMIN-as-organizer): full organizer-wide view.
        Page<EventResponseDTO> result = eventService.getMyEvents(
                organizerUuid, fromDateTime, toDateTime, venue, salesStatus, page, size, sortBy);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @GetMapping("/by-organizer")
    // Platform staff, not SUPER_ADMIN alone. AuthenticatedCaller.PLATFORM_STAFF_ROLES
    // is this service's definition of "sees every organizer's events, not just their
    // own", and both product roles are already in it — they can open an unpublished
    // event (callerMayViewUnpublished) and list inactive ones. This endpoint being
    // SUPER_ADMIN-only was inconsistent with that rule rather than a tighter
    // decision, so the console's organiser filter 403'd for exactly the staff whose
    // remit is reviewing events. Read-only, so PRODUCT_OFFICER is included; the
    // write side (approve/publish) stays SUPER_ADMIN + PRODUCT_MANAGER.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
    @Operation(
            summary = "List a specific organizer's events (platform staff)",
            description = """
                    Returns a paginated list of **non-deleted** events owned by the
                    supplied `organizerUuid` (the organizer's stable cross-service id —
                    the same uuid carried in the `tenantUserUuid` field on every event),
                    including both `active=true` and `active=false` events.

                    Open to **platform staff** — `SUPER_ADMIN`, `PRODUCT_OFFICER` and
                    `PRODUCT_MANAGER` — which is the same set that may already view any
                    organizer's unpublished and inactive events. This is the
                    platform-wide tool for inspecting *any* organizer's events by id —
                    unlike `GET /events/my`, which is always scoped to the caller's own
                    JWT. An `EVENT_ORGANIZER` is still refused **403**.

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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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
                    - **category** — one wire code from `GET /events/categories` (64 values;
                      an unknown code is a 400 before the controller runs).
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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "rejected": false,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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

            @Parameter(description = "Category filter: a `code` from `GET /events/categories`")
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
            // Everyone else, whatever their roles, gets the anonymous listing:
            // published events only, organizer id stripped.
            log.debug("Listing active events (public scope) from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                    from, to, venue, country, category, page, size, sortBy);
            result = eventService.getActiveOnlyEvents(fromDateTime, toDateTime, venue, country, category, page, size, sortBy);
        }
        return ResponseEntity.ok(ApiResult.ok("Active events retrieved successfully", result));
    }

    @GetMapping("/inactive")
    // Authenticated only. The inactive listing = drafts + admin-rejected events;
    // it must never be public (it previously dumped every organizer's unpublished
    // catalogue to anonymous callers). Organizers are self-scoped in the
    // handler; only SUPER_ADMIN reaches the fleet-wide moderation view.
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
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
                    - **SUPER_ADMIN** sees every tenant's.
                    - Anyone else is `401` (no session) or `403` (any other role).

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
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 80.00, "fullPrice": 120.00, "tierName": "Early bird", "categoryName": "Standard", "soldOut": false }
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
                                            "seatCategories": null,
                                            "priceFrom": null
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
            ),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "No session", value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Neither EVENT_ORGANIZER nor SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Not an organizer", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)",
                                      "data": null
                                    }
                                    """)))
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

            @Parameter(description = "Category filter: a `code` from `GET /events/categories`")
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
            // Only SUPER_ADMIN reaches here (@PreAuthorize): the fleet-wide view.
            log.debug("Listing inactive events (platform scope) from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
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
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
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
                                            "categoryPrice": 150.00,
                                            "currentPrice": 100.00,
                                            "currentTierName": "Early bird",
                                            "priceTiers": [
                                              { "name": "Early bird", "price": 100.00, "endsAt": "2026-06-01T23:59:59+02:00", "quantity": 100, "remaining": 63, "status": "ACTIVE" },
                                              { "name": "Phase 1", "price": 120.00, "endsAt": "2026-06-10T23:59:59+02:00", "quantity": null, "status": "UPCOMING" }
                                            ],
                                            "sections": [
                                              { "section": "A", "seatCount": 25, "price": 150.00 },
                                              { "section": "B", "seatCount": 25, "price": 150.00 }
                                            ]
                                          },
                                          {
                                            "name": "GA",
                                            "description": "General admission",
                                            "categoryPrice": 60.00,
                                            "currentPrice": 60.00,
                                            "currentTierName": null,
                                            "priceTiers": [],
                                            "sections": [
                                              { "section": "F", "seatCount": 50, "price": 60.00 }
                                            ]
                                          }
                                        ],
                                        "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                            "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                            "totalCapacity": 500,
                                            "availableTickets": 420,
                                            "active": true,
                                            "createdAt": "2026-04-25T08:00:00",
                                            "updatedAt": "2026-05-02T15:00:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 60.00, "fullPrice": 60.00, "tierName": null, "categoryName": "GA", "soldOut": false }
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
                    Creates a new event for the authenticated **EVENT_ORGANIZER**.

                    The authenticated principal's `organizerUuid` JWT claim becomes the owning
                    `tenantUserUuid`, and the event's `country` is taken from the caller's JWT
                    `country` claim — neither is part of the request body.

                    Every event belongs to an organizer. A **SUPER_ADMIN** passes the role check
                    but has no `organizerUuid` to own an event, so it is answered **400** "Events
                    belong to an organizer. Sign in with the organizer's account to create one."
                    Create the event from the organizer's own account.

                    The request is `multipart/form-data` with two parts:
                    - `event` — JSON body matching `CreateEventRequest` (title, description, venue, category, location, startDateTime, endDateTime, totalCapacity).
                    - `eventBanner` — optional image file (JPEG/PNG/WEBP — GIF is not accepted; max 10 MB).

                    Validation:
                    - `startDateTime` and `endDateTime` must be **in the future**, and `endDateTime` must be **after** `startDateTime`.
                    - `category` is required: a `code` from `GET /events/categories`.
                    - `location.latitude` ∈ [-90, 90]; `location.longitude` ∈ [-180, 180].
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = CreateEventMultipartRequest.class),
                            encoding = {
                                    @Encoding(name = "event", contentType = MediaType.APPLICATION_JSON_VALUE),
                                    @Encoding(name = "eventBanner", contentType = "image/png, image/jpeg, image/webp")
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
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777734000000",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": false,
                                        "createdAt": "2026-05-02T15:00:00",
                                        "updatedAt": "2026-05-02T15:00:00",
                                        "seatCategories": null,
                                        "priceFrom": null
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "No organizer to own the event, or the sales window "
                    + "does not fit the event",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Staff account (e.g. SUPER_ADMIN)",
                                    value = EXAMPLE_STAFF_CANNOT_CREATE_EVENT),
                            @ExampleObject(name = "Organizer session without its organizer id",
                                    value = EXAMPLE_SESSION_MISSING_ORGANIZER),
                            @ExampleObject(name = "Sales close after the event ends", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Ticket sales must close by the time the event ends.", "data": null }
                                    """),
                            @ExampleObject(name = "Sales close before they open", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Ticket sales must close after they open.", "data": null }
                                    """)
                    })),
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
        // A staff account (SUPER_ADMIN passes the role check) has no organizer
        // id, and every event must belong to one. Say so, rather than the
        // service's "sign in again", which only fits an organizer's broken session
        // and sent staff round in circles. Same status (400) as before.
        if (tenantUserUuid == null && !hasRole(authentication, "ROLE_EVENT_ORGANIZER")) {
            log.info("Event create refused: staff account, no organizer to own the event title={}",
                    request.getTitle());
            throw new com.innbucks.eventservice.exception.BadRequestException(STAFF_CANNOT_CREATE_EVENT);
        }
        log.info("Creating event tenantUserUuid={} country={} title={} venue={} hasBanner={}",
                tenantUserUuid, country, request.getTitle(), request.getVenue(),
                eventBanner != null && !eventBanner.isEmpty());
        EventResponseDTO created = eventService.createEvent(tenantUserUuid, country, request, eventBanner);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Event created successfully", created));
    }

    // POST is an ALIAS for PUT here, and it is not REST pedantry — it is the
    // only verb that reaches us. Cloudflare's WAF on the innbucks.co.zw zone
    // blocks PUT-with-a-multipart-body outright: the browser's preflight
    // succeeds (OPTIONS carries no body), so the FE sends the upload and
    // Cloudflare answers 403 with an HTML block page BEFORE origin. That page
    // carries no Access-Control-Allow-Origin, so the browser cannot read the
    // 403 either and surfaces a bare "Network error" — nothing reaches nginx,
    // the gateway or this service, and no log anywhere names the cause.
    // Measured on the ZW cell 2026-09-21 with an identical 300 KB body: PUT
    // multipart 403 (x4, Cloudflare HTML); POST multipart, PUT with a JSON
    // body, PUT with no body and DELETE all reached us (401 JSON). So the
    // trigger is PUT+multipart specifically, and POST is the way through.
    // PUT is KEPT so no existing client breaks and so the endpoint is correct
    // again the moment the WAF exception lands.
    @RequestMapping(
            method = {RequestMethod.PUT, RequestMethod.POST},
            value = "/{id}/banner",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Replace the event banner image",
            description = """
                    Replaces an existing event's banner with a newly uploaded image — the
                    write twin of `GET /events/{id}/banner`.

                    Send `multipart/form-data` with a single `eventBanner` part. The image
                    must be **JPG, PNG or WEBP** and **under 10 MB**; the payload's real
                    format is verified by its magic bytes, so renaming a file or faking the
                    `Content-Type` is rejected.

                    **`POST` and `PUT` are accepted on this path and behave identically.**
                    Prefer **`POST`** from a browser: the Cloudflare WAF in front of this
                    deployment blocks `PUT` carrying a multipart body before it reaches the
                    server, which a client sees only as an unexplained network failure.

                    `PUT /events/{id}` (JSON) does NOT carry the image — this is the only
                    way to change a banner after creation. Use `DELETE /events/{id}/banner`
                    to remove one without uploading a replacement.

                    Authorization: **EVENT_ORGANIZER** may replace the banner only on their
                    own event; **SUPER_ADMIN** on any. Returns the full updated event.

                    **`bannerUrl` carries a `?v=` version that changes on every replace**, so
                    render the returned value verbatim and caches resolve themselves. The
                    bytes are served `Cache-Control: public, max-age=3600` and the PATH is
                    fixed, so without that version a client would keep showing the previous
                    image for up to an hour. No client-side cache-busting is needed.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banner replaced",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Banner replaced", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event banner updated successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "title": "Summer Concert (Updated)",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777740000000",
                                        "startDateTime": "2026-06-15T19:00:00Z", "endDateTime": "2026-06-15T22:00:00Z",
                                        "totalCapacity": 600, "availableTickets": 520, "active": true
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "No file sent, file too large, or not a JPG/PNG/WEBP image",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No file", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Please choose an image to upload.", "data": null }
                                    """),
                            @ExampleObject(name = "Too large", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That image is too large. Please use one under 10 MB.", "data": null }
                                    """),
                            @ExampleObject(name = "Wrong type", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Please upload a JPG, PNG, or WEBP image.", "data": null }
                                    """),
                            @ExampleObject(name = "Not really an image", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Please upload a valid image file (JPG, PNG, or WEBP).", "data": null }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Not the owning organizer",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You are not authorized to update this event", "data": null }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> replaceEventBanner(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestPart("eventBanner") MultipartFile eventBanner,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Replacing banner eventId={} tenantUserUuid={} size={}",
                id, tenantUserUuid, eventBanner == null ? 0 : eventBanner.getSize());
        EventResponseDTO updated = eventService.replaceEventBanner(tenantUserUuid, role, id, eventBanner);
        return ResponseEntity.ok(ApiResult.ok("Event banner updated successfully", updated));
    }

    @DeleteMapping("/{id}/banner")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Remove the event banner image",
            description = """
                    Clears the event's banner without uploading a replacement — the event
                    then renders with the client's placeholder.

                    **Idempotent**: an event that already has no banner returns `200`, not
                    `404`, so a repeated tap is harmless. The returned event's `bannerUrl`
                    is `null` afterwards.

                    Authorization matches the replace endpoint: **EVENT_ORGANIZER** on their
                    own event, **SUPER_ADMIN** on any.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banner removed (or there was none)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Banner removed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event banner removed successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "title": "Summer Concert (Updated)",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe", "category": "CONCERT",
                                        "bannerUrl": null,
                                        "startDateTime": "2026-06-15T19:00:00Z", "endDateTime": "2026-06-15T22:00:00Z",
                                        "totalCapacity": 600, "availableTickets": 520, "active": true
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Not the owning organizer",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You are not authorized to update this event", "data": null }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> deleteEventBanner(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        log.info("Clearing banner eventId={} tenantUserUuid={}", id, tenantUserUuid);
        EventResponseDTO updated = eventService.deleteEventBanner(tenantUserUuid, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event banner removed successfully", updated));
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
                // OWASP A03: stop the browser MIME-sniffing the stored bytes into
                // an executable type (e.g. HTML/JS) regardless of the served
                // Content-Type — defence-in-depth alongside upload magic-byte checks.
                .header("X-Content-Type-Options", "nosniff")
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

        @Schema(type = "string", format = "binary", description = "Optional banner image (JPEG/PNG/WEBP — GIF is not accepted; max 10 MB).")
        public MultipartFile eventBanner;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Update event",
            description = """
                    Updates an existing event.

                    Authorization:
                    - Requires **EVENT_ORGANIZER** (their own event) or **SUPER_ADMIN**.

                    Behavior:
                    - Fields omitted from the body remain unchanged (partial update).
                    - If `totalCapacity` changes, `availableTickets` is adjusted by the capacity difference.
                    - `salesStartDateTime` and `salesEndDateTime` (market-local time, like the event's) are
                      checked against the values the event will have after this update: sales open before the
                      event ends, close after they open, and close no later than the event ends. Moving the
                      event's end before a stored sales time is refused too; send both in one request.
                      `null` removes either. Every change to them is recorded
                      (`GET /events/{id}/sales-window/history`).
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
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777737600000",
                                        "startDateTime": "2026-06-15T19:00:00+02:00", "endDateTime": "2026-06-15T22:00:00+02:00",
                                        "salesStartDateTime": null,
                                        "salesEndDateTime": "2026-06-15T18:00:00+02:00",
                                        "salesStatus": "ON_SALE",
                                        "totalCapacity": 600,
                                        "availableTickets": 520,
                                        "active": true,
                                        "createdAt": "2026-04-25T08:00:00+02:00",
                                        "updatedAt": "2026-05-02T16:00:00+02:00",
                                        "seatCategories": null,
                                        "priceFrom": null
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "The update would leave the event's times out of order",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Sales close after the event ends", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Ticket sales must close by the time the event ends.", "data": null }
                                    """),
                            @ExampleObject(name = "Sales close before they open", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Ticket sales must close after they open.", "data": null }
                                    """),
                            @ExampleObject(name = "Sales open after the event ends", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Ticket sales must open before the event ends.", "data": null }
                                    """),
                            @ExampleObject(name = "End before start", value = """
                                    { "code": "400 BAD_REQUEST", "message": "The event end time must be after the start time.", "data": null }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                            """))),
            @ApiResponse(responseCode = "403", description = "Another organizer's event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "You are not authorized to update this event", "data": null }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                            """)))
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

    @PutMapping("/{id}/booking-questions")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Replace the event's booking questions",
            description = """
                    Replaces the WHOLE list of questions asked for each ticket at checkout (V16), in the order
                    to ask them. Send the `id` of each question to keep (answers already given stay linked to
                    it); leave `id` out for a new question; a stored question left out is removed (answers
                    already given keep its label). `{"questions": []}` removes them all.

                    At most 10 questions. Types: TEXT (maxLength 1 to 500, default 100), NUMBER, DATE, CHOICE
                    (2 to 20 different options of at most 60 characters), CHECKBOX (required = must be ticked).
                    `categoryIds` limits a question to some ticket categories; [] asks every ticket.

                    The event's organizer or a SUPER_ADMIN only. A refusal names every problem by its place in
                    the list and changes nothing.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved; every question with its id",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Booking questions saved",
                              "data": {
                                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                "questions": [
                                  { "id": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e01", "label": "T-shirt size", "type": "CHOICE",
                                    "required": true, "helpText": "Unisex sizes", "options": ["S", "M", "L", "XL"],
                                    "maxLength": null, "categoryIds": [] },
                                  { "id": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e02", "label": "Emergency contact (name and phone)",
                                    "type": "TEXT", "required": true, "helpText": null, "options": [], "maxLength": 120,
                                    "categoryIds": [] },
                                  { "id": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e03", "label": "Date of birth", "type": "DATE",
                                    "required": true, "helpText": "Runners in the 21 km must be 18 or older on race day.",
                                    "options": [], "maxLength": null, "categoryIds": ["8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11"] },
                                  { "id": "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e04", "label": "Send me news about future races",
                                    "type": "CHECKBOX", "required": false, "helpText": null, "options": [], "maxLength": null,
                                    "categoryIds": [] }
                                ]
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "A question breaks a rule; data names each by its place",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Validation failed",
                              "data": {
                                "questions[0].options": "A choice question needs 2 to 20 different options of at most 60 characters.",
                                "questions[3].label": "Two questions can't have the same label."
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "403", description = "Not this event's organizer or a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "403 FORBIDDEN", "message": "You are not authorized to update this event", "data": null }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<com.innbucks.eventservice.dto.BookingQuestionsResponseDTO>> replaceBookingQuestions(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestBody com.innbucks.eventservice.dto.BookingQuestionsRequestDTO request,
            Authentication authentication) {
        UUID tenantUserUuid = com.innbucks.eventservice.security.AuthenticatedCaller.organizerUuid(authentication);
        String role = getCurrentRole(authentication);
        return ResponseEntity.ok(ApiResult.ok("Booking questions saved",
                eventService.replaceBookingQuestions(tenantUserUuid, role, id, request)));
    }

    @GetMapping("/{id}/terms/{version}")
    @SecurityRequirements()
    @Operation(
            summary = "One version of the event's terms and conditions",
            description = """
                    The exact text of a terms version (V16): what a booking with that `termsVersion` accepted.
                    Readable by whoever can read the event (anyone once published; its owner and staff while
                    it is a draft). Plain text with line breaks.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The terms version",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Terms retrieved",
                              "data": {
                                "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                "version": 1,
                                "text": "1. Entry fees are not refundable.\n2. Runners must collect their race pack in person.",
                                "createdAt": "2026-11-03T16:20:00+02:00"
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such version, or an event the caller cannot read",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Unknown version", value = """
                                    { "code": "404 NOT_FOUND", "message": "Terms not found", "data": null }
                                    """),
                            @ExampleObject(name = "Unknown or unpublished event", value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                                    """)
                    }))
    })
    public ResponseEntity<ApiResult<com.innbucks.eventservice.dto.EventTermsDTO>> getTerms(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @Parameter(description = "Terms version", example = "1") @PathVariable int version) {
        return ResponseEntity.ok(ApiResult.ok("Terms retrieved", eventService.getTerms(id, version)));
    }

    @GetMapping("/{id}/sales-window/history")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Who set or moved the event's ticket sales window, and when",
            description = """
                    The record of every change to the event's `salesStartDateTime` and `salesEndDateTime`
                    (V22), newest first, at most 200. One `CREATED` entry when the event was created with
                    either set, then one `CHANGED` entry for each update that moved either. Each entry has
                    both sides before and after; `null` means none (on sale once published / sales close
                    when the event ends). Times are the market's local time with its offset.

                    The record cannot be edited or deleted. The event's organizer reads their own event's
                    record (a change made by InnBucks staff reads `"changedBy": "InnBucks"`); SUPER_ADMIN,
                    PRODUCT_MANAGER and PRODUCT_OFFICER read any event's. Anyone else, and any unknown or
                    deleted event, is the same 404.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The record, newest first",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Created with a sales start, then a sales end added", value = """
                            {
                              "code": "200 OK",
                              "message": "Sales window history retrieved",
                              "data": [
                                {
                                  "changeType": "CHANGED",
                                  "oldSalesStartDateTime": "2026-11-14T10:00:00+02:00",
                                  "newSalesStartDateTime": "2026-11-14T10:00:00+02:00",
                                  "oldSalesEndDateTime": null,
                                  "newSalesEndDateTime": "2026-12-05T18:00:00+02:00",
                                  "changedBy": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                  "changedByOrganizer": true,
                                  "changedAt": "2026-11-10T14:32:00+02:00"
                                },
                                {
                                  "changeType": "CREATED",
                                  "oldSalesStartDateTime": null,
                                  "newSalesStartDateTime": "2026-11-14T10:00:00+02:00",
                                  "oldSalesEndDateTime": null,
                                  "newSalesEndDateTime": null,
                                  "changedBy": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                  "changedByOrganizer": true,
                                  "changedAt": "2026-11-03T16:40:00+02:00"
                                }
                              ]
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "No session",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                            """))),
            @ApiResponse(responseCode = "404", description = "Unknown or deleted event, or another organizer's",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<List<com.innbucks.eventservice.dto.SalesWindowChangeDTO>>> salesWindowHistory(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication) {
        UUID organizerUuid = com.innbucks.eventservice.security.AuthenticatedCaller.organizerUuid(authentication);
        boolean staff = com.innbucks.eventservice.security.AuthenticatedCaller.isPlatformStaff(authentication);
        return ResponseEntity.ok(ApiResult.ok("Sales window history retrieved",
                eventService.salesWindowHistory(organizerUuid, staff, id)));
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')")
    @Operation(
            summary = "Activate event",
            description = """
                    Flips the event's `active` flag to `true`.

                    Newly created events start with `active=false`; the owning
                    **EVENT_ORGANIZER** calls this endpoint once the event
                    is ready to be surfaced via `GET /events/active` and
                    `GET /events/by-country`.

                    **Review step (V17, per cell, `EVENTS_REVIEW_REQUIRED`).** While it
                    is OFF this publishes as described. While it is ON, the owner's
                    call on an event not yet approved SUBMITS it for review instead:
                    the answer is 200 with message "Event submitted for review",
                    `active: false` and `reviewStatus: SUBMITTED`, and the review staff
                    are emailed. Calling it again while it waits changes nothing. A
                    rejected event the owner publishes again is resubmitted. Staff
                    (SUPER_ADMIN, PRODUCT_MANAGER) publishing an event awaiting review
                    approve it; staff publishing a DRAFT get 409. An event approved
                    once is published by its owner with no further review.
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
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777737600000",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": true,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T16:00:00",
                                        "seatCategories": null,
                                        "priceFrom": null
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
        // Review step on (V17): the organizer's publish submitted it instead.
        boolean submitted = !activated.isActive() && activated.getReviewStatus() == ReviewStatus.SUBMITTED;
        return ResponseEntity.ok(ApiResult.ok(
                submitted ? SUBMITTED_FOR_REVIEW_MESSAGE : "Event activated successfully", activated));
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
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner?v=1777739400000",
                                        "startDateTime": "2026-06-15T19:00:00", "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 500,
                                        "active": false,
                                        "rejected": false,
                                        "createdAt": "2026-04-25T08:00:00",
                                        "updatedAt": "2026-05-02T16:30:00",
                                        "seatCategories": null,
                                        "priceFrom": null
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PRODUCT_MANAGER')")
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

                    **V17:** optional body `{"reason": "..."}` (up to 1000 characters,
                    markup stripped), sent to the organizer and shown to them as
                    `rejectionReason`. **Required while the review step is on** (400
                    otherwise). With the step on, the organizer edits the event and
                    publishes it again to resubmit it.
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
                                        "seatCategories": null,
                                        "priceFrom": null
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Review step on and no reason given",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Reason required", value = EXAMPLE_REJECTION_REASON_REQUIRED))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Event not found\",\"data\":null}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> rejectEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestBody(required = false) @Valid RejectEventRequestDTO body,
            Authentication authentication
    ) {
        log.info("Rejecting event eventId={} admin={}", id, authentication.getName());
        EventResponseDTO rejected = eventService.rejectEvent(id, body == null ? null : body.reason());
        return ResponseEntity.ok(ApiResult.ok("Event rejected successfully", rejected));
    }

    static final String SUBMITTED_FOR_REVIEW_MESSAGE = "Event submitted for review";

    static final String STAFF_CANNOT_CREATE_EVENT =
            "Events belong to an organizer. Sign in with the organizer's account to create one.";

    static final String EXAMPLE_STAFF_CANNOT_CREATE_EVENT = """
            {"code":"400 BAD_REQUEST","message":"Events belong to an organizer. Sign in with the organizer's account to create one.","data":null}""";

    static final String EXAMPLE_SESSION_MISSING_ORGANIZER = """
            {"code":"400 BAD_REQUEST","message":"Your session is missing organizer information. Please sign in again.","data":null}""";

    private static boolean hasRole(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    static final String EXAMPLE_REJECTION_REASON_REQUIRED = """
            {"code":"400 BAD_REQUEST","message":"Please say why the event is not approved. The organizer sees your note.","data":null}""";

    static final String EXAMPLE_NOT_SUBMITTED_YET = """
            {"code":"409 CONFLICT","message":"The organizer hasn't submitted this event for review yet.","data":null}""";

    @GetMapping("/review-queue")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PRODUCT_MANAGER','PRODUCT_OFFICER')")
    @Operation(
            summary = "Events awaiting approval (staff review queue)",
            description = """
                    **SUPER_ADMIN, PRODUCT_MANAGER or PRODUCT_OFFICER** (read-only).
                    Every event submitted for review and not yet decided
                    (`reviewStatus: SUBMITTED`), oldest submission first, with the
                    organizer's details. Approve with `PUT /events/{id}/approve`, reject
                    with `PUT /events/{id}/reject` and a reason. Empty while the review
                    step is off on this cell (V17, `EVENTS_REVIEW_REQUIRED`): nothing is
                    submitted then, events go live directly and staff are told.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The queue",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "One event awaiting approval", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Review queue retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11",
                                            "eventNo": null,
                                            "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                            "organizer": { "businessName": "Harare Runners Club", "businessAddress": "12 Samora Machel Ave, Harare", "businessEmail": "events@harare-runners.example" },
                                            "title": "Harare Pink Fun Run",
                                            "settlementCode": "HARAREPINK",
                                            "description": "5 km and 10 km routes through the city centre.",
                                            "venue": "Harare Gardens",
                                            "country": "Zimbabwe", "category": "FUN_RUN",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "https://dtx.innbucks.co.zw/foundry/events/4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11/banner?v=1762182300",
                                            "startDateTime": "2026-11-28T07:00:00+02:00", "endDateTime": "2026-11-28T11:00:00+02:00",
                                            "salesStartDateTime": "2026-11-10T09:00:00+02:00",
                                            "salesEndDateTime": null,
                                            "salesStatus": "NOT_PUBLISHED",
                                            "termsVersion": null,
                                            "termsText": null,
                                            "bookingQuestions": null,
                                            "totalCapacity": 500,
                                            "availableTickets": 500,
                                            "active": false,
                                            "rejected": false,
                                            "reviewStatus": "SUBMITTED",
                                            "submittedAt": "2026-11-03T17:05:00+02:00",
                                            "createdAt": "2026-11-03T16:40:00+02:00",
                                            "updatedAt": "2026-11-03T17:05:00+02:00",
                                            "seatCategories": null,
                                            "priceFrom": { "currentPrice": 15.00, "fullPrice": 15.00, "tierName": null, "categoryName": "10 km", "soldOut": false }
                                          }
                                        ],
                                        "totalElements": 1,
                                        "totalPages": 1,
                                        "size": 20,
                                        "number": 0,
                                        "first": true,
                                        "last": true,
                                        "numberOfElements": 1,
                                        "empty": false
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Not platform staff")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getReviewQueue(
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(ApiResult.ok("Review queue retrieved", eventService.getReviewQueue(page, size)));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PRODUCT_MANAGER')")
    @Operation(
            summary = "Approve (un-reject) event (admin moderation)",
            description = """
                    **SUPER_ADMIN or PRODUCT_MANAGER.**

                    **Review step OFF (V17, the default):** clears a previous rejection
                    (`rejected=false`). The event stays `active=false` — the owning
                    organizer re-publishes it with `PUT /events/{id}/activate` when
                    ready (now permitted again because the rejection is cleared).
                    Calling approve on an event that was never rejected is a no-op
                    that returns the event unchanged (still `rejected=false`).

                    **Review step ON:** approves an event awaiting review (or a rejected
                    one) AND publishes it: `active: true`, `reviewStatus: APPROVED`, and
                    the organizer is told it is live. A DRAFT never submitted is 409;
                    an event already approved is returned unchanged (an event its
                    organizer unpublished is not put back live).
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
                                        "seatCategories": null,
                                        "priceFrom": null
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "409", description = "Review step on and the organizer has not submitted it",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Not submitted yet", value = EXAMPLE_NOT_SUBMITTED_YET))),
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

    @GetMapping("/internal/{id}")
    @SecurityRequirements()
    @Operation(
            summary = "Get event by id (internal)",
            description = """
                    Internal endpoint: sibling services call this to read an event
                    INCLUDING `tenantUserUuid`, which the public `GET /events/{id}`
                    strips for anonymous callers so organizer ids can't be
                    enumerated — and a server-side service call is anonymous.
                    booking-service uses it for its event-ownership checks (bookings
                    and scan-report analytics) and to capture the owning organizer
                    onto new bookings. Draft/rejected events are returned too — an
                    ownership check must keep working after an event is unpublished.

                    Requires the `X-Internal-Token` shared secret. Missing or wrong
                    token returns 401. The gateway also blocks /events/internal/**
                    from the public internet via the event-internal-deny rule; this
                    controller check is defence in depth.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Event returned with tenantUserUuid intact",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Internal event lookup", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event retrieved successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "title": "Summer Concert",
                                        "venue": "Harare Gardens",
                                        "country": "Zimbabwe",
                                        "category": "CONCERT",
                                        "startDateTime": "2026-06-15T19:00:00",
                                        "endDateTime": "2026-06-15T22:00:00",
                                        "totalCapacity": 500,
                                        "availableTickets": 420,
                                        "active": true
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Event does not exist or is soft-deleted (handled by the global exception handler today)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token")
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> getEventInternal(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /events/internal/{} — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        return ResponseEntity.ok(ApiResult.ok("Event retrieved successfully", eventService.getEventInternal(id)));
    }

    @GetMapping("/internal/sales-window")
    @SecurityRequirements()
    @Operation(
            summary = "May this event be sold right now? (internal)",
            description = """
                    Internal endpoint: booking-service asks this before EVERY sale and refuses the booking
                    unless `status` is `ON_SALE`. Read live from the event row on every call, never cached.

                    `status` is one of `ON_SALE`, `NOT_YET_ON_SALE` (sales open at `salesStartDateTime`),
                    `SALES_CLOSED` (sales closed at `salesEndDateTime`), `ENDED` (the event's end has passed),
                    `NOT_PUBLISHED` (draft or rejected) and `NOT_FOUND` (no such event, or deleted).
                    booking-service also asks before extending a booking's hold for payment, so a payment can
                    no longer be started once sales have closed. Always a 200 for a valid id, an unknown event included, so a
                    caller's circuit-breaker fallback can only mean "event-service could not be asked".

                    Times are UTC (`Z`) on this S2S path. Requires the `X-Internal-Token` shared secret; the
                    gateway's `event-internal-deny` route makes `/events/internal/**` unreachable from the
                    public internet.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event's sales window",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Published, sales not open yet", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Sales window resolved",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "status": "NOT_YET_ON_SALE",
                                        "salesStartDateTime": "2026-11-14T08:00:00Z",
                                        "salesEndDateTime": "2026-12-05T16:00:00Z",
                                        "endDateTime": "2026-12-05T21:00:00Z",
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "termsVersion": null,
                                        "bookingQuestions": []
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Sales closed before the event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Sales window resolved",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "status": "SALES_CLOSED",
                                        "salesStartDateTime": "2026-11-14T08:00:00Z",
                                        "salesEndDateTime": "2026-12-05T16:00:00Z",
                                        "endDateTime": "2026-12-05T21:00:00Z",
                                        "tenantUserUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                        "termsVersion": null,
                                        "bookingQuestions": []
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "No such event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Sales window resolved",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "status": "NOT_FOUND",
                                        "salesStartDateTime": null,
                                        "salesEndDateTime": null,
                                        "endDateTime": null,
                                        "tenantUserUuid": null,
                                        "termsVersion": null,
                                        "bookingQuestions": null
                                      }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Missing or invalid X-Internal-Token", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<SalesWindowDTO>> salesWindow(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /events/internal/sales-window — missing or wrong X-Internal-Token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        return ResponseEntity.ok(ApiResult.ok("Sales window resolved", eventService.salesWindow(eventId)));
    }

    static final int MAX_LIVE_COUNT_ORGANIZERS = 50;

    @GetMapping("/internal/live-event-count")
    @SecurityRequirements()
    @Operation(
            summary = "How many events these organizers have on sale (internal)",
            description = """
                    Internal endpoint: user-service asks this before it suspends a business's ticketing
                    service, and refuses while the answer is above zero. Counts published events (active,
                    not rejected, not deleted) that have not ended, read live. `organizerUuids` is a
                    comma-separated list of up to 50 organizer user uuids; an empty answer is `0`.
                    Requires the `X-Internal-Token` shared secret; the gateway's `event-internal-deny`
                    route makes `/events/internal/**` unreachable from the public internet.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The count",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Two events on sale", value = """
                                    {"code":"200 OK","message":"Live events counted","data":{"liveEvents":2}}
                                    """))),
            @ApiResponse(responseCode = "400", description = "More than 50 organizers",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Too many", value = """
                                    {"code":"400 BAD_REQUEST","message":"Ask about at most 50 organizers at a time.","data":null}
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Unauthorized", value = """
                                    {"code":"401 UNAUTHORIZED","message":"Missing or invalid X-Internal-Token","data":null}
                                    """)))
    })
    public ResponseEntity<ApiResult<java.util.Map<String, Long>>> liveEventCount(
            @RequestParam(name = "organizerUuids", required = false) List<UUID> organizerUuids,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /events/internal/live-event-count — missing or wrong X-Internal-Token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        List<UUID> organizers = organizerUuids == null ? List.of() : organizerUuids;
        if (organizers.size() > MAX_LIVE_COUNT_ORGANIZERS) {
            return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                    "Ask about at most " + MAX_LIVE_COUNT_ORGANIZERS + " organizers at a time."));
        }
        return ResponseEntity.ok(ApiResult.ok("Live events counted",
                java.util.Map.of("liveEvents", eventService.liveEventCount(organizers))));
    }

    @GetMapping("/internal/ended")
    @SecurityRequirements()
    @Operation(
            summary = "Event ids that ended in a window (internal)",
            description = """
                    Internal endpoint: booking-service calls this to decide which
                    events to bill organizer commission on. Commission is invoiced
                    AFTER an event has run, not in the month its tickets happened
                    to sell, so the billing period selects on the event's END.

                    `from` is inclusive, `to` is EXCLUSIVE — an event ending exactly
                    at midnight belongs to the period starting, so two adjacent
                    calls count it once rather than twice or never. Both are UTC,
                    matching how `end_date_time` is stored.

                    Returns ids only. The caller already holds the bookings and
                    needs nothing else; returning full events would put every
                    organizer's unpublished drafts on an S2S response for no reason.
                    Soft-deleted events are excluded.

                    Requires the `X-Internal-Token` shared secret; the gateway's
                    `event-internal-deny` route also makes `/events/internal/**`
                    unreachable from the public internet.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ids of events that ended in the window",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Two events ended", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Ended events retrieved successfully",
                                      "data": [
                                        "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      ]
                                    }
                                    """),
                            @ExampleObject(name = "None ended", value = """
                                    { "code": "200 OK", "message": "Ended events retrieved successfully", "data": [] }
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Missing or invalid X-Internal-Token", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UUID>>> eventsEndedBetween(
            @Parameter(description = "Window start, inclusive (UTC)")
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Window end, EXCLUSIVE (UTC)")
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /events/internal/ended — missing or wrong X-Internal-Token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        return ResponseEntity.ok(ApiResult.ok("Ended events retrieved successfully",
                eventService.eventIdsEndedBetween(from, to)));
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
     * platform-wide view. Every other caller of {@code GET /events} and
     * {@code /events/active} (anonymous, a customer, a super-app scanner, a
     * leftover token of the retired TEAM_MEMBER role) gets the anonymous
     * listing: published events only.
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
