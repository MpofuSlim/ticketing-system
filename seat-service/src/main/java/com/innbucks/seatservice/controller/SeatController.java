package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatLookupResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.security.MinTier;
import com.innbucks.seatservice.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/seats")
@RequiredArgsConstructor
@Slf4j
@Validated  // makes @Min/@Max fire on the @RequestParam `limit` below
@Tag(name = "Seats", description = "Seat discovery, locking, confirmation, and release operations.")
public class SeatController {

    // Hard cap on the GET /seats/available `limit` sample size. Booking-service
    // only needs a handful of candidate seats; this keeps the response small and
    // stops a hostile limit=999999 from forcing a large random sort server-side.
    private static final int MAX_AVAILABLE_LIMIT = 1000;

    private final SeatService seatService;

    @GetMapping
    @Operation(summary = "List seats by category", description = "Returns all seats for a given seat category.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Seats returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SeatResponseDTO.class),
                            examples = @ExampleObject(name = "Seats by category", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seats retrieved successfully",
                                      "data": [
                                        {
                                          "id": "11111111-2222-3333-4444-555555555555",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "sectionLabel": "A",
                                          "seatNumber": 12,
                                          "status": "AVAILABLE"
                                        },
                                        {
                                          "id": "22222222-3333-4444-5555-666666666666",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "sectionLabel": "A",
                                          "seatNumber": 13,
                                          "status": "BOOKED"
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<SeatResponseDTO>>> getSeatsByCategory(
            @Parameter(description = "Seat category UUID") @RequestParam UUID categoryId
    ) {
        log.debug("GET /seats categoryId={}", categoryId);
        List<SeatResponseDTO> result = seatService.getSeatsByCategory(categoryId);
        return ResponseEntity.ok(ApiResult.ok("Seats retrieved successfully", result));
    }

    @GetMapping("/available")
    @Operation(summary = "List available seats", description = "Returns only seats that are currently AVAILABLE for a category.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Available seats returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SeatResponseDTO.class),
                            examples = @ExampleObject(name = "Available seats", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Available seats retrieved successfully",
                                      "data": [
                                        {
                                          "id": "11111111-2222-3333-4444-555555555555",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "sectionLabel": "A",
                                          "seatNumber": 14,
                                          "status": "AVAILABLE"
                                        },
                                        {
                                          "id": "22222222-3333-4444-5555-666666666666",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "sectionLabel": "A",
                                          "seatNumber": 15,
                                          "status": "AVAILABLE"
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<SeatResponseDTO>>> getAvailableSeats(
            @Parameter(description = "Seat category UUID") @RequestParam UUID categoryId,
            @Parameter(description = "Optional cap: return at most this many AVAILABLE seats, chosen at random "
                    + "(1.." + "1000). Booking-service uses it to sample a few candidates instead of pulling the "
                    + "entire pool of a large category. Omit to return every available seat, e.g. for a seat map.")
            @RequestParam(required = false) @Min(1) @Max(MAX_AVAILABLE_LIMIT) Integer limit
    ) {
        log.debug("GET /seats/available categoryId={} limit={}", categoryId, limit);
        List<SeatResponseDTO> result = (limit == null)
                ? seatService.getAvailableSeats(categoryId)
                : seatService.getAvailableSeats(categoryId, limit);
        return ResponseEntity.ok(ApiResult.ok("Available seats retrieved successfully", result));
    }

    @GetMapping("/{id}/lookup")
    @Operation(summary = "Lookup seat details", description = "Returns full seat details including event, category, price, and status. Used by booking-service to resolve a seatId without trusting client-supplied data.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Seat details returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SeatLookupResponseDTO.class),
                            examples = @ExampleObject(name = "Seat lookup", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat details retrieved successfully",
                                      "data": {
                                        "seatId": "11111111-2222-3333-4444-555555555555",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "categoryName": "VIP",
                                        "sectionLabel": "A",
                                        "seatNumber": 12,
                                        "price": 100.00,
                                        "status": "AVAILABLE"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Seat not found")
    })
    public ResponseEntity<ApiResult<SeatLookupResponseDTO>> lookupSeat(@PathVariable UUID id) {
        log.debug("GET /seats/{}/lookup", id);
        return ResponseEntity.ok(ApiResult.ok("Seat details retrieved successfully",
                seatService.lookupSeat(id)));
    }

    @PostMapping("/{id}/lock")
    @MinTier(2)
    @Operation(summary = "Lock seat", description = "Locks an available seat for the authenticated user for a short TTL window. Requires tier 2.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Seat locked",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SeatLockResponseDTO.class),
                            examples = @ExampleObject(name = "Seat locked", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat locked successfully",
                                      "data": {
                                        "seatId": "11111111-2222-3333-4444-555555555555",
                                        "sectionLabel": "A",
                                        "seatNumber": 12,
                                        "categoryName": "VIP",
                                        "status": "LOCKED",
                                        "message": "Seat locked successfully",
                                        "expiresInSeconds": 600
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Seat not found or unavailable")
    })
    public ResponseEntity<ApiResult<SeatLockResponseDTO>> lockSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        log.info("POST /seats/{}/lock userEmail={}", id, userEmail);
        return ResponseEntity.ok(ApiResult.ok("Seat locked successfully",
                seatService.lockSeat(id, userEmail)));
    }

    @PostMapping("/{id}/confirm")
    @MinTier(2)
    @Operation(summary = "Confirm seat", description = "Confirms a locked seat after successful payment. Requires tier 2.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Seat confirmed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SeatResponseDTO.class),
                            examples = @ExampleObject(name = "Seat confirmed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat confirmed successfully",
                                      "data": {
                                        "id": "11111111-2222-3333-4444-555555555555",
                                        "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                        "categoryName": "VIP",
                                        "sectionLabel": "A",
                                        "seatNumber": 12,
                                        "status": "BOOKED"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Lock expired or belongs to another user")
    })
    public ResponseEntity<ApiResult<SeatResponseDTO>> confirmSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        log.info("POST /seats/{}/confirm userEmail={}", id, userEmail);
        return ResponseEntity.ok(ApiResult.ok("Seat confirmed successfully",
                seatService.confirmSeat(id, userEmail)));
    }

    @PostMapping("/{id}/release")
    @MinTier(2)
    @Operation(summary = "Release seat lock", description = "Releases a seat lock owned by the authenticated user. Requires tier 2.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Seat released",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(name = "Seat released", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Seat released successfully",
                                      "data": null
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Seat not found or lock belongs to another user")
    })
    public ResponseEntity<ApiResult<Void>> releaseSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        log.info("POST /seats/{}/release userEmail={}", id, userEmail);
        seatService.releaseSeat(id, userEmail);
        return ResponseEntity.ok(ApiResult.ok("Seat released successfully", null));
    }
}
