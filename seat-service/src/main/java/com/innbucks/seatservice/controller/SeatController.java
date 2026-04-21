package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
@Tag(name = "Seats", description = "Seat discovery, locking, confirmation, and release operations.")
public class SeatController {

    private final SeatService seatService;

    @GetMapping
    @Operation(summary = "List seats by category", description = "Returns all seats for a given seat category.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Seats returned")
    })
    public ResponseEntity<ApiResult<List<SeatResponseDTO>>> getSeatsByCategory(
            @Parameter(description = "Seat category UUID") @RequestParam UUID categoryId
    ) {
        log.debug("GET /seats categoryId={}", categoryId);
        return ResponseEntity.ok(ApiResult.ok("Seats retrieved successfully",
                seatService.getSeatsByCategory(categoryId)));
    }

    @GetMapping("/available")
    @Operation(summary = "List available seats", description = "Returns only seats that are currently AVAILABLE for a category.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Available seats returned")
    })
    public ResponseEntity<ApiResult<List<SeatResponseDTO>>> getAvailableSeats(
            @Parameter(description = "Seat category UUID") @RequestParam UUID categoryId
    ) {
        log.debug("GET /seats/available categoryId={}", categoryId);
        return ResponseEntity.ok(ApiResult.ok("Available seats retrieved successfully",
                seatService.getAvailableSeats(categoryId)));
    }

    @PostMapping("/{id}/lock")
    @Operation(summary = "Lock seat", description = "Locks an available seat for the authenticated user for a short TTL window.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Seat locked"),
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
    @Operation(summary = "Confirm seat", description = "Confirms a locked seat after successful payment.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Seat confirmed"),
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
    @Operation(summary = "Release seat lock", description = "Releases a seat lock owned by the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Seat released"),
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
