package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Events", description = "Browse events publicly; agents can create/update/delete events.")
public class EventController {

    private final EventService eventService;

    @GetMapping
    @SecurityRequirements()
    @Operation(
            summary = "List active events",
            description = """
                    Returns a paginated list of **non-deleted** events.

                    Filtering:
                    - **from/to** are interpreted as calendar dates (`yyyy-MM-dd`).
                    - Internally they map to `[from at start-of-day .. to at end-of-day]` using the server's local timezone.
                    - **venue** matches case-insensitive substring (`LIKE %venue%`).
                    Sorting uses the persisted field name `dateTime` by default (internal storage is `LocalDateTime`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of events")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getAllEvents(
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
        log.debug("GET /events from={} to={} venue={} page={} size={} sortBy={}",
                from, to, venue, page, size, sortBy);
        Page<EventResponseDTO> result = eventService.getAllActiveEvents(
                fromDateTime, toDateTime, venue, page, size, sortBy);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
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
            @ApiResponse(responseCode = "200", description = "Event details"),
            @ApiResponse(responseCode = "400", description = "Event not found",
                    content = @Content(schema = @Schema(example = "{\"error\":\"Event not found\"}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> getEventById(
            @Parameter(description = "Event UUID") @PathVariable UUID id
    ) {
        log.debug("GET /events/{}", id);
        return ResponseEntity.ok(ApiResult.ok("Event retrieved successfully", eventService.getEventById(id)));
    }

    @GetMapping("/by-province")
    @SecurityRequirements()
    @Operation(
            summary = "List active events by province",
            description = "Returns a paginated list of non-deleted events for a specific province."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of events for the province")
    })
    public ResponseEntity<ApiResult<Page<EventResponseDTO>>> getEventsByProvince(
            @Parameter(description = "Province code, e.g. HRE")
            @RequestParam Province province,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "10") int size,

            @Parameter(description = "Sort field name (must match an entity property), ascending")
            @RequestParam(defaultValue = "dateTime") String sortBy
    ) {
        log.debug("GET /events/by-province province={} page={} size={} sortBy={}",
                province, page, size, sortBy);
        Page<EventResponseDTO> result = eventService.getEventsByProvince(province, page, size, sortBy);
        return ResponseEntity.ok(ApiResult.ok("Events retrieved successfully", result));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    @Operation(
            summary = "Create event",
            description = """
                    Creates a new event for the authenticated **AGENT** or **ADMIN**.

                    The authenticated principal (`Authentication#getName()`) becomes the owning `agentId`.

                    Validation:
                    - `dateTime` must be **in the future** (validated on the request DTO).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not AGENT/ADMIN"),
            @ApiResponse(responseCode = "422", description = "Validation errors",
                    content = @Content(schema = @Schema(example = "{\"title\":\"Title is required\"}")))
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> createEvent(
            @Valid @RequestBody CreateEventRequestDTO request,
            Authentication authentication
    ) {
        String agentId = authentication.getName();
        log.info("Creating event agentId={} title={} venue={}", agentId, request.getTitle(), request.getVenue());
        EventResponseDTO created = eventService.createEvent(agentId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Event created successfully", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    @Operation(
            summary = "Update event",
            description = """
                    Updates an existing event.

                    Authorization:
                    - Requires **AGENT** or **ADMIN**.
                    - AGENT can update only their own event. ADMIN can update any event.

                    Behavior:
                    - Fields omitted from the body remain unchanged (partial update).
                    - If `totalCapacity` changes, `availableTickets` is adjusted by the capacity difference.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Not found or not authorized",
                    content = @Content(schema = @Schema(example = "{\"error\":\"You are not authorized to update this event\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not AGENT/ADMIN")
    })
    public ResponseEntity<ApiResult<EventResponseDTO>> updateEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateEventRequestDTO request,
            Authentication authentication
    ) {
        String agentId = authentication.getName();
        String role = getCurrentRole(authentication);
        log.info("Updating event eventId={} agentId={}", id, agentId);
        EventResponseDTO updated = eventService.updateEvent(agentId, role, id, request);
        return ResponseEntity.ok(ApiResult.ok("Event updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT','ADMIN')")
    @Operation(
            summary = "Delete event (soft delete)",
            description = """
                    Soft-deletes an event (`deleted=true`).

                    Authorization:
                    - Requires **AGENT** or **ADMIN**.
                    - AGENT can delete only their own event. ADMIN can delete any event.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "400", description = "Not found or not authorized",
                    content = @Content(schema = @Schema(example = "{\"error\":\"You are not authorized to delete this event\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not AGENT/ADMIN")
    })
    public ResponseEntity<ApiResult<Void>> deleteEvent(
            @Parameter(description = "Event UUID") @PathVariable UUID id,
            Authentication authentication
    ) {
        String agentId = authentication.getName();
        String role = getCurrentRole(authentication);
        log.info("Deleting event eventId={} agentId={}", id, agentId);
        eventService.deleteEvent(agentId, role, id);
        return ResponseEntity.ok(ApiResult.ok("Event deleted successfully", null));
    }

    private String getCurrentRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .findFirst()
                .map(authority -> authority.getAuthority())
                .orElse("");
    }
}
