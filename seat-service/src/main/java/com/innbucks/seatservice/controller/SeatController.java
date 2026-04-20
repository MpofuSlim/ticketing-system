package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.SeatLockResponseDTO;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.service.SeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
@Tag(name = "Seats", description = "Seat discovery, locking, confirmation, and release operations.")
public class SeatController {

    private final SeatService seatService;

    @GetMapping
    @Operation(summary = "List seats by category", description = "Returns all seats for a given seat category.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seats returned")
    })
    public ResponseEntity<List<SeatResponseDTO>> getSeatsByCategory(
            @Parameter(description = "Seat category UUID")
            @RequestParam UUID categoryId
    ) {
        return ResponseEntity.ok(seatService.getSeatsByCategory(categoryId));
    }

    @GetMapping("/available")
    @Operation(summary = "List available seats", description = "Returns only seats that are currently AVAILABLE for a category.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Available seats returned")
    })
    public ResponseEntity<List<SeatResponseDTO>> getAvailableSeats(
            @Parameter(description = "Seat category UUID")
            @RequestParam UUID categoryId
    ) {
        return ResponseEntity.ok(seatService.getAvailableSeats(categoryId));
    }

    @PostMapping("/{id}/lock")
    @Operation(summary = "Lock seat", description = "Locks an available seat for the authenticated user for a short TTL window.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seat locked"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Seat not found or unavailable")
    })
    public ResponseEntity<SeatLockResponseDTO> lockSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(seatService.lockSeat(id, userEmail));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm seat", description = "Confirms a locked seat after successful payment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seat confirmed"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Lock expired or belongs to another user")
    })
    public ResponseEntity<SeatResponseDTO> confirmSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(seatService.confirmSeat(id, userEmail));
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release seat lock", description = "Releases a seat lock owned by the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Seat released"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Seat not found or lock belongs to another user")
    })
    public ResponseEntity<Void> releaseSeat(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        seatService.releaseSeat(id, userEmail);
        return ResponseEntity.noContent().build();
    }
}
