package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanTicketRequestDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.service.TicketScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate-side ticket redemption. One endpoint, two callers:
 *
 * <ul>
 *   <li>EVENT_ORGANIZER scanning a ticket for their own event;</li>
 *   <li>TEAM_MEMBER (a gate-staff user the organizer onboarded via
 *       {@code POST /event-organizer/team-members}) scanning a ticket
 *       for any event their parent organizer owns.</li>
 * </ul>
 *
 * <p>Redemption is single-shot per ticket — enforced atomically by an
 * UPDATE WHERE redeemed_at IS NULL inside the service. A second scan
 * comes back as {@code ALREADY_REDEEMED} with the original {@code
 * redeemedAt} + {@code redeemedByName} so the gate-staff sees who
 * scanned it first.
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Ticket Scan",
     description = "Gate-side ticket redemption for EVENT_ORGANIZER and TEAM_MEMBER scanners.")
@SecurityRequirement(name = "bearerAuth")
public class TicketScanController {

    private final TicketScanService ticketScanService;

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER')")
    @Operation(
            summary = "Redeem a ticket at the gate",
            description = "Single-shot per ticket. ALLOWED on the first scan; ALREADY_REDEEMED on every " +
                          "subsequent one (with the original scanner + timestamp, so the gate-staff " +
                          "knows who first scanned it). WRONG_ORGANIZER when the scanner doesn't work " +
                          "for the event's organizer. TICKET_NOT_FOUND on a bogus QR. " +
                          "BOOKING_NOT_CONFIRMED when the ticket exists but the booking is PENDING / " +
                          "CANCELLED (i.e. not actually paid for). Always returns 200 — the {@code " +
                          "status} field carries the verdict so the scanner-app's UX branch is " +
                          "deterministic regardless of HTTP layer behaviour. " +
                          "Requires **EVENT_ORGANIZER** or **TEAM_MEMBER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Scan result",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "ALLOWED", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "ALLOWED",
                                                "ticketNumber": "20260619-48291X",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                "redeemedAt": "2026-06-19T19:42:11",
                                                "redeemedByName": "Tariro Chikomo"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "ALREADY_REDEEMED", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "ALREADY_REDEEMED",
                                                "ticketNumber": "20260619-48291X",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                "redeemedAt": "2026-06-19T19:42:11",
                                                "redeemedByName": "Tariro Chikomo"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_ORGANIZER", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "WRONG_ORGANIZER",
                                                "ticketNumber": "20260619-48291X",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "TICKET_NOT_FOUND", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "TICKET_NOT_FOUND",
                                                "ticketNumber": "BOGUS-12345"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "BOOKING_NOT_CONFIRMED", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "BOOKING_NOT_CONFIRMED",
                                                "ticketNumber": "20260619-48291X",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789"
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "ticketNumber missing or blank",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "ticketNumber is required",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not an EVENT_ORGANIZER or TEAM_MEMBER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - insufficient role",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<ScanTicketResponseDTO>> scan(
            @Valid @RequestBody ScanTicketRequestDTO request,
            Authentication authentication
    ) {
        String scannerDisplayName = authentication == null ? null : authentication.getName();
        ScanTicketResponseDTO result = ticketScanService.scan(request.getTicketNumber(), scannerDisplayName);
        return ResponseEntity.ok(ApiResult.ok("Scan result", result));
    }
}
