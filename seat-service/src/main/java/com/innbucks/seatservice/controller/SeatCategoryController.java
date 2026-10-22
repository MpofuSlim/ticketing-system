package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CategoryCapacityDTO;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.dto.PriceTiersRequestDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.security.JwtAuthDetails;
import com.innbucks.seatservice.service.SeatCategoryAnalyticsService;
import com.innbucks.seatservice.service.SeatCategoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/seat-categories")
@RequiredArgsConstructor
@Slf4j
@Validated  // makes @Min/@Max fire on @RequestParam pagination params (caps DoS via size=999999)
@Tag(name = "Seat Categories", description = "Manage seat categories for events.")
public class SeatCategoryController {

    private final SeatCategoryService categoryService;
    private final SeatCategoryAnalyticsService analyticsService;

    /**
     * Upper bound on @RequestParam {@code size} across listing endpoints.
     * Stops a hostile {@code size=999999} from materialising the entire
     * per-category bookings list (could be tens of thousands of rows) in
     * a single request. 100 is generous for the kiosk/admin scroll UX,
     * a 400 for everyone else.
     */
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping
    @SecurityRequirements()
    @Operation(summary = "List categories by event",
            description = "Returns all active seat categories for a given event, each with a live "
                    + "`availableSeats` = totalSeats − active (PENDING + CONFIRMED) bookings, so the FE can "
                    + "show tickets remaining per category and customers can track seats left as they sell. "
                    + "If booking-service is unreachable this degrades to the stored mirror rather than "
                    + "failing the listing. `price` is the full price; `currentPrice` is what a booking made "
                    + "now pays (the ACTIVE tier's price, named by `currentTierName`), so show `currentPrice` as "
                    + "the ticket price. `priceTiers` lists every tier in the order they apply, each with "
                    + "`sold`, `remaining` and `status` (ACTIVE / UPCOMING / ENDED / SOLD_OUT); counts are left "
                    + "out when booking-service cannot be asked. Public read.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Categories returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Categories by event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Categories retrieved successfully",
                                      "data": [
                                        {
                                          "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "name": "VIP",
                                          "description": "Front rows",
                                          "price": 150.00,
                                          "currentPrice": 100.00,
                                          "currentTierName": "Early bird",
                                          "priceTiers": [
                                            { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                              "startsAt": null, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "maxPerCustomer": 4, "refundsReopen": true, "sold": 37, "remaining": 63, "status": "ACTIVE" },
                                            { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                              "startsAt": null, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true, "sold": 0, "status": "UPCOMING" }
                                          ],
                                          "availableSeats": 13,
                                          "sections": [
                                            { "section": "A", "seatCount": 25 },
                                            { "section": "B", "seatCount": 25 }
                                          ]
                                        },
                                        {
                                          "seatCategoryId": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "name": "GA",
                                          "description": "General admission",
                                          "price": 60.00,
                                          "currentPrice": 60.00,
                                          "currentTierName": null,
                                          "priceTiers": [],
                                          "availableSeats": 38,
                                          "sections": [
                                            { "section": "F", "seatCount": 50 }
                                          ]
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<CreateCategoryResponseDTO>>> getByEvent(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId
    ) {
        log.debug("GET /seat-categories eventId={}", eventId);
        List<CreateCategoryResponseDTO> result = categoryService.getCategoriesByEvent(eventId);
        return ResponseEntity.ok(ApiResult.ok("Categories retrieved successfully", result));
    }

    @GetMapping("/{id}")
    @SecurityRequirements()
    @Operation(summary = "Get category capacity",
            description = "Returns a single category's capacity + pricing (totalSeats, availableSeats, price, "
                    + "priceTiers, eventId). Used by booking-service to seed its per-category ticket inventory "
                    + "counter and price a booking: it prices each ticket from `priceTiers`, counting the tickets "
                    + "each tier has sold itself. The tiers carry their terms only (no counts or status); the "
                    + "list endpoint shows those. Public read.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Category found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryCapacityDTO.class),
                            examples = @ExampleObject(name = "Category capacity", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Category retrieved successfully",
                                      "data": {
                                        "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "VIP",
                                        "price": 150.00,
                                        "priceTiers": [
                                          { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                            "startsAt": null, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "maxPerCustomer": 4, "refundsReopen": true },
                                          { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                            "startsAt": null, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true }
                                        ],
                                        "totalSeats": 50,
                                        "availableSeats": 50
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Category not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Seat category not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<CategoryCapacityDTO>> getCategoryCapacity(
            @Parameter(description = "Seat category UUID") @PathVariable UUID id
    ) {
        log.debug("GET /seat-categories/{}", id);
        CategoryCapacityDTO result = categoryService.getCategoryCapacity(id);
        return ResponseEntity.ok(ApiResult.ok("Category retrieved successfully", result));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Create category",
            description = "Creates a seat category for an event. Requires EVENT_ORGANIZER role. Optional "
                    + "`priceTiers` (each { name, price, startsAt?, endsAt?, quantity?, maxPerCustomer?, "
                    + "refundsReopen? }, in the order they apply) sell tickets below `price` in phases: the "
                    + "first tier open is the price. A tier opens at startsAt (or as the tiers before it "
                    + "close) and closes at endsAt or after quantity tickets, whichever comes first. "
                    + "maxPerCustomer caps one buyer's tickets at the tier (the rest cost the next tier's "
                    + "price); refundsReopen false keeps a refunded ticket's place taken. startsAt and "
                    + "endsAt are the organizer's local time with no zone, e.g. 2026-09-08T23:59:59.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Category created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Category created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Seat category created successfully",
                                      "data": {
                                        "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "VIP",
                                        "description": "Front rows",
                                        "price": 150.00,
                                        "currentPrice": 100.00,
                                        "currentTierName": "Early bird",
                                        "priceTiers": [
                                          { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                            "startsAt": null, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "maxPerCustomer": 4, "refundsReopen": true, "sold": 0, "remaining": 100, "status": "ACTIVE" },
                                          { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                            "startsAt": null, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true, "sold": 0, "status": "UPCOMING" }
                                        ],
                                        "availableSeats": 50,
                                        "sections": [
                                          { "section": "A", "seatCount": 25, "imageUrl": "https://cdn.innbucks.co.zw/sections/vip-a.png" },
                                          { "section": "B", "seatCount": 25, "imageUrl": null }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation or domain error",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Tier not below the price", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The price of \\"Early bird\\" must be lower than the ticket price (150.00).",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Tier never closes", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "\\"Early bird\\" needs an end date, a number of tickets, or both.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Tier already over", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The end of \\"Early bird\\" must be in the future.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Field validation", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "priceTiers[0].price": "Tier price must be greater than 0" }
                                            }
                                            """)
                            }))
    })
    public ResponseEntity<ApiResult<CreateCategoryResponseDTO>> createCategory(
            @Valid @RequestBody CreateCategoryRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        UUID callerOrganizerUuid = organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        String authHeader = httpRequest.getHeader("Authorization");
        log.info("POST /seat-categories eventId={} name={} requesterEmail={} isAdmin={}",
                request.getEventId(), request.getName(), requesterEmail, isAdmin);
        CreateCategoryResponseDTO created = categoryService.createCategory(
                request, callerOrganizerUuid, requesterEmail, isAdmin, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Seat category created successfully", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Update category",
            description = "Updates a seat category's name, description, and price. The seat layout "
                    + "(sections / total seats) and the owning event are immutable here — changing them "
                    + "would collide with already-generated seats and live bookings. Requires the owning "
                    + "EVENT_ORGANIZER or SUPER_ADMIN. Returns the updated category in the same shape as the "
                    + "list endpoint (sections rebuilt from persisted seats, live availableSeats).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Category updated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Category updated", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat category updated successfully",
                                      "data": {
                                        "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "VIP",
                                        "description": "Premium front-row seats, now with lounge access",
                                        "price": 160.00,
                                        "currentPrice": 100.00,
                                        "currentTierName": "Early bird",
                                        "priceTiers": [
                                          { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                            "startsAt": null, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 100, "maxPerCustomer": 4, "refundsReopen": true, "sold": 37, "remaining": 63, "status": "ACTIVE" },
                                          { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                            "startsAt": null, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true, "sold": 0, "status": "UPCOMING" }
                                        ],
                                        "availableSeats": 37,
                                        "sections": [
                                          { "section": "A", "seatCount": 25 },
                                          { "section": "B", "seatCount": 25 }
                                        ]
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation error (blank name, non-positive price, price not above an open price tier)",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Bad price", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Price must be greater than 0.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Price not above an open tier", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The ticket price must be higher than the price tier \\"Phase 1\\" (120.00). Lower or remove that tier first.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not the owning EVENT_ORGANIZER / SUPER_ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Category not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Seat category not found",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Another live category in the event already uses that name",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Duplicate name", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Category 'VIP' already exists for this event",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<CreateCategoryResponseDTO>> updateCategory(
            @Parameter(description = "Seat category UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        UUID callerOrganizerUuid = organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        String authHeader = httpRequest.getHeader("Authorization");
        log.info("PUT /seat-categories/{} name={} requesterEmail={} isAdmin={}",
                id, request.getName(), requesterEmail, isAdmin);
        CreateCategoryResponseDTO updated = categoryService.updateCategory(
                id, request, callerOrganizerUuid, requesterEmail, isAdmin, authHeader);
        return ResponseEntity.ok(ApiResult.ok("Seat category updated successfully", updated));
    }

    @PutMapping("/{id}/price-tiers")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Set the price tiers",
            description = "Replaces the category's price tiers with `tiers`, in the order they apply (e.g. Early "
                    + "bird, then Phase 1); `[]` removes them all. The first tier still open is the price, and once "
                    + "all have closed the category's `price` applies. A tier opens at `startsAt` (none: as "
                    + "the tiers before it close) and closes at `endsAt` or after `quantity` tickets, whichever "
                    + "comes first; it needs at least one of the two. `maxPerCustomer` caps how many tickets one "
                    + "buyer (by phone, else email) gets at the tier; their further tickets cost the next open "
                    + "tier's price. `refundsReopen` (default true) false keeps a refunded ticket's place "
                    + "taken; an expired, unpaid hold always frees its place. The whole list is replaced, so "
                    + "send every field you want to keep. **Send the `id` of every "
                    + "tier you keep**, so the tickets it has sold still count toward its quantity; a tier without "
                    + "an id is new, and a stored tier left out is deleted. `startsAt` and `endsAt` are the "
                    + "organizer's local time with no zone (2026-09-08T23:59:59 runs through 8 September). Each tier's price must be "
                    + "lower than the category's price. Tickets already booked keep the price they were booked "
                    + "at. Requires the owning EVENT_ORGANIZER or SUPER_ADMIN. Returns the category in the list "
                    + "endpoint's shape.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Price tiers set",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CreateCategoryResponseDTO.class),
                            examples = @ExampleObject(name = "Price tiers set", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Price tiers set",
                                      "data": {
                                        "seatCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "name": "VIP",
                                        "description": "Premium front-row seats, now with lounge access",
                                        "price": 160.00,
                                        "currentPrice": 100.00,
                                        "currentTierName": "Early bird",
                                        "priceTiers": [
                                          { "id": "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", "name": "Early bird", "price": 100.00,
                                            "startsAt": null, "endsAt": "2026-09-08T23:59:59+02:00", "quantity": 150, "maxPerCustomer": 4, "refundsReopen": false, "sold": 37, "remaining": 113, "status": "ACTIVE" },
                                          { "id": "7d2f3e4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a", "name": "Phase 1", "price": 120.00,
                                            "startsAt": null, "endsAt": "2026-09-20T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true, "sold": 0, "status": "UPCOMING" },
                                          { "id": "8e3a4f5b-6c7d-4e8f-a0b1-2c3d4e5f6a7b", "name": "Phase 2", "price": 140.00,
                                            "startsAt": "2026-09-21T00:00:00+02:00", "endsAt": "2026-09-27T23:59:59+02:00", "quantity": null, "maxPerCustomer": null, "refundsReopen": true, "sold": 0, "status": "UPCOMING" }
                                        ],
                                        "availableSeats": 37,
                                        "sections": [
                                          { "section": "A", "seatCount": 25 },
                                          { "section": "B", "seatCount": 25 }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "A tier is not below the price, never closes, is already over, is badly formed, "
                            + "or names an id that is not this category's",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Not below the price", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The price of \\"Phase 2\\" must be lower than the ticket price (160.00).",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Never closes", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "\\"Phase 2\\" needs an end date, a number of tickets, or both.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "End in the past", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The end of \\"Phase 2\\" must be in the future.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Unreadable end", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The end of \\"Phase 2\\" must be a date and time like 2026-09-08T23:59:59.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Start not before the end", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The start of \\"Phase 2\\" must be before its end.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Unreadable start", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The start of \\"Phase 2\\" must be a date and time like 2026-09-05T18:00:00.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Bad customer limit", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The limit per customer for \\"Phase 2\\" must be at least 1.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Duplicate name", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Two price tiers are named \\"Phase 1\\". Give each tier its own name.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Not this category's tier", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Price tier 9f4b5a6c-7d8e-4f9a-b1c2-3d4e5f6a7b8c does not belong to this category.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Field validation", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "tiers[2].price": "Tier price must be greater than 0" }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing/invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invalid token", value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Not the owning EVENT_ORGANIZER / SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Forbidden", value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Category not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    { "code": "404 NOT_FOUND", "message": "Seat category not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<CreateCategoryResponseDTO>> setPriceTiers(
            @Parameter(description = "Seat category UUID") @PathVariable UUID id,
            @Valid @RequestBody PriceTiersRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.info("PUT /seat-categories/{}/price-tiers tiers={} requesterEmail={} isAdmin={}", id,
                request.getTiers() == null ? 0 : request.getTiers().size(), requesterEmail, isAdmin);
        CreateCategoryResponseDTO updated = categoryService.setPriceTiers(id, request, organizerUuid(authentication),
                requesterEmail, isAdmin, httpRequest.getHeader("Authorization"));
        return ResponseEntity.ok(ApiResult.ok("Price tiers set", updated));
    }

    @GetMapping("/analytics")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Get event analytics",
            description = "Returns aggregated analytics for every seat category in an event: " +
                    "category metadata, per-status seat counts, paginated bookings + per-category revenue, " +
                    "and event-level rollup totals across all categories. " +
                    "Restricted to EVENT_ORGANIZER because the response includes customer emails. " +
                    "Tolerates booking-service downtime — `bookingServiceReachable=false` indicates " +
                    "the bookings sections reflect no-data, not zero-bookings. " +
                    "Pagination applies per-category: each category's `bookings.items` is sliced to " +
                    "the requested page; the aggregate counts/revenue stay computed across the full set."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Analytics returned (empty categories list if event has none)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventAnalyticsDTO.class),
                            examples = @ExampleObject(name = "Event analytics", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event analytics retrieved successfully",
                                      "data": {
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "categoryCount": 2,
                                        "totals": {
                                          "totalSeats": 100,
                                          "availableSeats": 40,
                                          "lockedSeats": 5,
                                          "bookedSeats": 55,
                                          "totalBookings": 53,
                                          "pendingBookings": 3,
                                          "paidBookings": 47,
                                          "cancelledBookings": 3,
                                          "pendingRevenue": 300.00,
                                          "paidRevenue": 4700.00,
                                          "potentialRevenue": 8000.00,
                                          "mostRecentBookingAt": "2026-05-02T15:45:00"
                                        },
                                        "categories": [
                                          {
                                            "category": {
                                              "id": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "name": "VIP",
                                              "description": "Front rows",
                                              "price": 100.00,
                                              "totalSeats": 50,
                                              "cachedAvailableSeats": 12,
                                              "createdAt": "2026-04-25T08:00:00",
                                              "updatedAt": "2026-05-02T15:45:00"
                                            },
                                            "seatStatusCounts": { "total": 50, "available": 12, "locked": 3, "booked": 35 },
                                            "bookings": {
                                              "totalRecords": 38,
                                              "pendingRecords": 2,
                                              "paidRecords": 33,
                                              "cancelledRecords": 3,
                                              "pendingRevenue": 200.00,
                                              "paidRevenue": 3300.00,
                                              "potentialRevenue": 5000.00,
                                              "mostRecentBookingAt": "2026-05-02T15:45:00",
                                              "pageNumber": 0,
                                              "pageSize": 20,
                                              "totalPages": 2,
                                              "items": [
                                                {
                                                  "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                  "userEmail": "alice@example.com",
                                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                  "status": "CONFIRMED",
                                                  "confirmationNumber": "INN-20260502-AB12CD",
                                                  "seatId": "11111111-2222-3333-4444-555555555555",
                                                  "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                                  "categoryName": "VIP",
                                                  "rowLabel": "A",
                                                  "seatNumber": 12,
                                                  "ticketNumber": "20260502-3FQ7M-X9K2D",
                                                  "priceAtBooking": 100.00,
                                                  "bookedAt": "2026-05-02T15:45:00",
                                                  "updatedAt": "2026-05-02T15:45:00",
                                                  "expiresAt": null
                                                },
                                                {
                                                  "bookingId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                                  "userEmail": "bob@example.com",
                                                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                  "status": "PENDING",
                                                  "confirmationNumber": "INN-20260502-EF34A9",
                                                  "seatId": "22222222-3333-4444-5555-666666666666",
                                                  "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                                  "categoryName": "VIP",
                                                  "rowLabel": "A",
                                                  "seatNumber": 13,
                                                  "ticketNumber": "20260502-8TN4W-2HC6R",
                                                  "priceAtBooking": 100.00,
                                                  "bookedAt": "2026-05-02T15:43:30",
                                                  "updatedAt": "2026-05-02T15:43:30",
                                                  "expiresAt": "2026-05-02T15:48:30"
                                                }
                                              ]
                                            }
                                          },
                                          {
                                            "category": {
                                              "id": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                              "name": "GA",
                                              "description": "General admission",
                                              "price": 60.00,
                                              "totalSeats": 50,
                                              "cachedAvailableSeats": 28,
                                              "createdAt": "2026-04-25T08:00:00",
                                              "updatedAt": "2026-05-02T14:00:00"
                                            },
                                            "seatStatusCounts": { "total": 50, "available": 28, "locked": 2, "booked": 20 },
                                            "bookings": {
                                              "totalRecords": 15,
                                              "pendingRecords": 1,
                                              "paidRecords": 14,
                                              "cancelledRecords": 0,
                                              "pendingRevenue": 100.00,
                                              "paidRevenue": 1400.00,
                                              "potentialRevenue": 3000.00,
                                              "mostRecentBookingAt": "2026-05-02T14:00:00",
                                              "pageNumber": 0,
                                              "pageSize": 20,
                                              "totalPages": 1,
                                              "items": []
                                            }
                                          }
                                        ],
                                        "bookingServiceReachable": true,
                                        "fetchedAt": "2026-05-02T15:46:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<EventAnalyticsDTO>> getEventAnalytics(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId,
            @Parameter(description = "Zero-based page index for the per-category bookings list (default 0)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size for the per-category bookings list (default 20, max 100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        String requesterEmail = authentication.getName();
        UUID callerOrganizerUuid = organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.info("GET /seat-categories/analytics eventId={} requesterEmail={} isAdmin={} page={} size={}",
                eventId, requesterEmail, isAdmin, page, size);
        String authHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        EventAnalyticsDTO analytics = analyticsService.getEventAnalytics(
                eventId, callerOrganizerUuid, requesterEmail, isAdmin, page, size, authHeader);
        return ResponseEntity.ok(ApiResult.ok("Event analytics retrieved successfully", analytics));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Delete category",
            description = "Soft-deletes a seat category. Requires EVENT_ORGANIZER role.\n\n"
                    + "**Refused with 409 while the category still has active (PENDING or CONFIRMED) "
                    + "bookings** — deleting it would strand every holder on a category the event no "
                    + "longer lists. CANCELLED bookings do not count, so a category whose sales were "
                    + "all refunded can be deleted.\n\n"
                    + "Changing the category's **price** is NOT restricted this way: a booking freezes "
                    + "`priceAtBooking` at purchase, so a reprice only sets what the next buyer pays.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Category deleted",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Category deleted", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat category deleted successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Category not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Category still has active bookings",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Has bookings", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "'VIP' has 12 active bookings and cannot be deleted. Cancel or refund them first.",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "booking-service unreachable, so the booking count could not be checked. "
                            + "The delete is refused rather than assumed safe; retry unchanged.",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Cannot verify", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Cannot verify whether this category has bookings right now. Please try again shortly.",
                                      "data": null
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<Void>> deleteCategory(
            @PathVariable UUID id,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String requesterEmail = authentication.getName();
        UUID callerOrganizerUuid = organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        String authHeader = httpRequest.getHeader("Authorization");
        log.info("DELETE /seat-categories/{} requesterEmail={} isAdmin={}", id, requesterEmail, isAdmin);
        categoryService.deleteCategory(id, callerOrganizerUuid, requesterEmail, isAdmin, authHeader);
        return ResponseEntity.ok(ApiResult.ok("Seat category deleted successfully", null));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }

    private static UUID organizerUuid(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getDetails() instanceof JwtAuthDetails details
                ? details.organizerUuid()
                : null;
    }
}
