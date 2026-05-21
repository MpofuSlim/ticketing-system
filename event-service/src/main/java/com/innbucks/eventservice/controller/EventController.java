package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Province;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
@Tag(name = "Events", description = "Browse events publicly; tenants can create/update/delete events.")
public class EventController {

    private final EventService eventService;

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
                    Sorting uses the persisted field name `dateTime` by default (internal storage is `LocalDateTime`).
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
                                            "tenantId": "tenant-001",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "province": "HRE",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "dateTime": "2026-06-15T19:00:00",
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
            @RequestParam(defaultValue = "0")   int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10")  int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "dateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        Page<EventResponseDTO> result;
        if (isOrganizerOnly(authentication)) {
            String tenantId = authentication.getName();
            log.debug("Listing events (organizer scope) tenantId={} from={} to={} venue={} page={} size={} sortBy={}",
                    tenantId, from, to, venue, page, size, sortBy);
            result = eventService.getMyEvents(tenantId, fromDateTime, toDateTime, venue, page, size, sortBy);
        } else {
            log.debug("Listing events (public scope) from={} to={} venue={} page={} size={} sortBy={}",
                    from, to, venue, page, size, sortBy);
            result = eventService.getAllActiveEvents(fromDateTime, toDateTime, venue, page, size, sortBy);
        }
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }


    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List the authenticated organizer's own events",
            description = """
                    Returns a paginated list of **non-deleted** events owned by the
                    authenticated principal (matched by `tenantId == Authentication#getName()`).
                    Includes both active=true and active=false events.

                    Filters and sorting follow the same rules as `GET /events`.

                    Use this instead of `GET /events` when the caller wants to see
                    only their own events. SUPER_ADMIN can also call this — they
                    will see only events they personally created (their own
                    Authentication#getName()), since this endpoint is scoped by
                    the principal. To see *every* tenant's events, SUPER_ADMIN
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
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "dateTime") String sortBy
    ) {
        String tenantId = authentication.getName();
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        log.info("GET /events/my tenantId={} from={} to={} venue={} page={} size={}",
                tenantId, from, to, venue, page, size);
        Page<EventResponseDTO> result = eventService.getMyEvents(
                tenantId, fromDateTime, toDateTime, venue, page, size, sortBy);
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

                    Filtering and sorting follow the same rules as `GET /events`.
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
                                            "tenantId": "tenant-001",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "province": "HRE",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "dateTime": "2026-06-15T19:00:00",
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

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0")   int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10")  int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "dateTime") String sortBy
    ) {
        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.atTime(LocalTime.MAX);
        Page<EventResponseDTO> result;
        if (isOrganizerOnly(authentication)) {
            String tenantId = authentication.getName();
            log.debug("Listing active events (organizer scope) tenantId={} from={} to={} venue={} page={} size={} sortBy={}",
                    tenantId, from, to, venue, page, size, sortBy);
            result = eventService.getMyActiveEvents(tenantId, fromDateTime, toDateTime, venue, page, size, sortBy);
        } else {
            log.debug("Listing active events (public scope) from={} to={} venue={} page={} size={} sortBy={}",
                    from, to, venue, page, size, sortBy);
            result = eventService.getActiveOnlyEvents(fromDateTime, toDateTime, venue, page, size, sortBy);
        }
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
        return ResponseEntity.ok(ApiResult.ok("Active events retrieved successfully", result));
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
                                        "tenantId": "tenant-001",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "province": "HRE",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "dateTime": "2026-06-15T19:00:00",
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

    @GetMapping("/by-province")
    @SecurityRequirements()
    @Operation(
            summary = "List active events by province",
            description = """
                    Returns a paginated list of non-deleted events for a specific province,
                    ordered by `dateTime` ascending so the soonest-starting event is first.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paged list of events for the province, earliest first",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventResponseDTO.class),
                            examples = @ExampleObject(name = "Events by province", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Events retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "eventNo": 1,
                                            "tenantId": "tenant-001",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "province": "HRE",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "dateTime": "2026-06-15T19:00:00",
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
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getEventsByProvince(
            @Parameter(description = "Province code, e.g. HRE")
            @RequestParam Province province,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") int size
    ) {
        log.debug("GET /events/by-province province={} page={} size={}", province, page, size);
        Page<EventResponseDTO> result = eventService.getEventsByProvince(province, page, size);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(HttpStatus.NOT_FOUND, "Not found"));
        }
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
                                            "tenantId": "tenant-001",
                                            "title": "Summer Concert",
                                            "description": "Open-air summer concert featuring local headliners.",
                                            "venue": "Harare Gardens",
                                            "province": "HRE",
                                            "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                            "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                            "dateTime": "2026-06-15T19:00:00",
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
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "dateTime") String sortBy
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

                    The authenticated principal (`Authentication#getName()`) becomes the owning `tenantId`.

                    The request is `multipart/form-data` with two parts:
                    - `event` — JSON body matching `CreateEventRequest` (title, description, venue, province, location, dateTime, totalCapacity).
                    - `eventBanner` — optional image file (JPEG/PNG/GIF/WEBP, max 5 MB).

                    Validation:
                    - `dateTime` must be **in the future**.
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
                                        "tenantId": "tenant-001",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "province": "HRE",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "dateTime": "2026-06-15T19:00:00",
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
            Authentication authentication
    ) {
        String tenantId = authentication.getName();
        log.info("Creating event tenantId={} title={} venue={} hasBanner={}",
                tenantId, request.getTitle(), request.getVenue(),
                eventBanner != null && !eventBanner.isEmpty());
        EventResponseDTO created = eventService.createEvent(tenantId, request, eventBanner);
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
                                        "tenantId": "tenant-001",
                                        "title": "Summer Concert (Updated)",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "province": "HRE",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "dateTime": "2026-06-15T19:00:00",
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
        String tenantId = authentication.getName();
        String role = getCurrentRole(authentication);
        log.info("Updating event eventId={} tenantId={} request={}", id, tenantId, request);
        EventResponseDTO updated = eventService.updateEvent(tenantId, role, id, request);
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
                    `GET /events/by-province`.
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
                                        "tenantId": "tenant-001",
                                        "title": "Summer Concert",
                                        "description": "Open-air summer concert featuring local headliners.",
                                        "venue": "Harare Gardens",
                                        "province": "HRE",
                                        "location": { "latitude": -17.8252, "longitude": 31.0335 },
                                        "bannerUrl": "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
                                        "dateTime": "2026-06-15T19:00:00",
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
        String tenantId = authentication.getName();
        String role = getCurrentRole(authentication);
        log.info("Activating event eventId={} tenantId={}", id, tenantId);
        EventResponseDTO activated = eventService.activateEvent(tenantId, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event activated successfully", activated));
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
        String tenantId = authentication.getName();
        String role = getCurrentRole(authentication);
        log.info("Deleting event eventId={} tenantId={}", id, tenantId);
        eventService.deleteEvent(tenantId, role, id);
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
