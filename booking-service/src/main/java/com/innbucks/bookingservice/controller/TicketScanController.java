package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.GateLookupRequestDTO;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanTicketRequestDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.GateLookupService;
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
     description = "Gate-side ticket redemption: the scanner app (EVENT_ORGANIZER, TEAM_MEMBER) and "
             + "gate scanners on the InnBucks super app (a CUSTOMER session whose phone an organizer "
             + "added to the event's team).")
@SecurityRequirement(name = "bearerAuth")
public class TicketScanController {

    private final TicketScanService ticketScanService;
    private final GateLookupService gateLookupService;

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER','CUSTOMER')")
    @Operation(
            summary = "Redeem a ticket at the gate",
            description = "Single-shot per ticket. ALLOWED on the first scan; ALREADY_REDEEMED on every " +
                          "subsequent one (with the original scanner + timestamp, so the gate-staff " +
                          "knows who first scanned it). WRONG_ORGANIZER when the scanner doesn't work " +
                          "for the event's organizer. TICKET_NOT_FOUND on a bogus QR. " +
                          "BOOKING_NOT_CONFIRMED when the ticket exists but the booking is PENDING / " +
                          "CANCELLED (i.e. not actually paid for). WRONG_EVENT_DAY when the ticket is " +
                          "genuine and the scanner is authorised, but today is not one of the event's " +
                          "market-local days — the ticket is NOT redeemed and stays valid for its own " +
                          "day, and the response carries `eventDate` so staff can say which day that " +
                          "is. " +
                          "**200 carries verdicts about the ticket; 503 means the server could not " +
                          "decide.** The scan result always arrives as 200 with the {@code status} " +
                          "field, so the scanner-app's verdict branch stays deterministic. The one " +
                          "exception is 503: if the event's dates cannot be confirmed (event-service " +
                          "unreachable), the scan is refused WITHOUT redeeming and the client should " +
                          "show a retry rather than turning the customer away — an outage is not a " +
                          "verdict about the ticket. Operators can disable the day rule entirely with " +
                          "`innbucks.scan.event-day-check.enabled=false`. " +
                          "**`eventId`** is the event the gate is scanning for: a ticket for any other " +
                          "event is WRONG_EVENT and is not redeemed. Optional for the scanner app. " +
                          "**Super-app scanners** (a CUSTOMER session) must send it: their access to that " +
                          "event is checked first (NOT_ASSIGNED_TO_EVENT when their number is not on the " +
                          "event's team, 503 when it cannot be checked), and a ticket of another event or " +
                          "organizer is WRONG_EVENT. ALLOWED and ALREADY_REDEEMED carry `categoryName`. " +
                          "Requires **EVENT_ORGANIZER**, **TEAM_MEMBER** or **CUSTOMER** role."
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
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                "redeemedAt": "2026-06-19T19:42:11Z",
                                                "redeemedByName": "Tariro Chikomo",
                                                "holderName": "Tendai Ncube",
                                                "categoryName": "VIP"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "ALREADY_REDEEMED", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "ALREADY_REDEEMED",
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                "redeemedAt": "2026-06-19T19:42:11Z",
                                                "redeemedByName": "Tariro Chikomo",
                                                "holderName": "Tendai Ncube",
                                                "categoryName": "VIP"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_ORGANIZER", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "WRONG_ORGANIZER",
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
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
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_EVENT_DAY", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "WRONG_EVENT_DAY",
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                "eventDate": "2026-06-19"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_EVENT", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "WRONG_EVENT",
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B",
                                                "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "NOT_ASSIGNED_TO_EVENT", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Scan result",
                                              "data": {
                                                "status": "NOT_ASSIGNED_TO_EVENT",
                                                "ticketNumber": "20260619-7KQ4M-9XZ2B"
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "ticketNumber missing or blank; or a super-app "
                            + "scanner sent no eventId",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "ticketNumber missing", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "ticketNumber": "ticketNumber is required" }
                                    }
                                    """),
                                    @ExampleObject(name = "eventId missing (super app)", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "eventId is required",
                                      "data": null
                                    }
                                    """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not an EVENT_ORGANIZER, TEAM_MEMBER or CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - insufficient role",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "The event's dates could not be confirmed, so the scan was refused "
                                  + "WITHOUT redeeming the ticket. Retryable — show an amber 'try "
                                  + "again', not a rejection: the ticket may well be valid. This is "
                                  + "the deliberate exception to the always-200 rule, because an "
                                  + "outage is not a verdict about the ticket.",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Event date unknown", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Could not confirm the event's date. Please try again.",
                                      "data": null
                                    }
                                    """),
                                    @ExampleObject(name = "Scanner access unknown (super app)", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Could not confirm you can scan for this event. Please try again.",
                                      "data": null
                                    }
                                    """)
                            }))
    })
    public ResponseEntity<ApiResult<ScanTicketResponseDTO>> scan(
            @Valid @RequestBody ScanTicketRequestDTO request,
            Authentication authentication
    ) {
        String scannerDisplayName = resolveScannerDisplayName(authentication);
        ScanTicketResponseDTO result = ticketScanService.scan(
                request.getTicketNumber(), request.getEventId(), scannerDisplayName);
        return ResponseEntity.ok(ApiResult.ok("Scan result", result));
    }

    @PostMapping("/lookup")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','TEAM_MEMBER','CUSTOMER')")
    @Operation(
            summary = "Find a booking by confirmation number (no-QR fallback)",
            description = "For a customer who cannot open the WhatsApp QR but has the confirmation number "
                          + "from their SMS. **Read-only — it admits nobody.** Returns the booking's tickets "
                          + "with each holder's name and whether it has been used; to admit a holder, send "
                          + "that ticket's `ticketNumber` to `POST /tickets/scan`, exactly as if the QR had "
                          + "been scanned (so ALREADY_REDEEMED, WRONG_EVENT_DAY and the scan audit all apply). "
                          + "The confirmation number is case-insensitive. "
                          + "**Check who you are admitting**: a confirmation number is easier to share than "
                          + "a QR, so compare the holder's name and ask for the phone number ending in "
                          + "`customerPhoneLast4`. "
                          + "Same authorization as the scan: WRONG_ORGANIZER / NOT_ASSIGNED_TO_EVENT when this "
                          + "scanner could not redeem the booking's tickets. Every outcome is 200 with a "
                          + "`status`; anything but FOUND carries only the echoed confirmation number. "
                          + "**`eventId`** is the event the gate is scanning for: another event's booking is "
                          + "WRONG_EVENT and none of its tickets are listed. Optional for the scanner app; "
                          + "**super-app scanners** must send it, and their access to the event is checked "
                          + "before the booking is read (NOT_ASSIGNED_TO_EVENT, or 503 when it cannot be "
                          + "checked). "
                          + "Requires **EVENT_ORGANIZER**, **TEAM_MEMBER** or **CUSTOMER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Lookup result",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "FOUND", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "FOUND",
                                                "confirmationNumber": "INN-20260901-3C8849",
                                                "customerName": "Tendai Ncube",
                                                "customerPhoneLast4": "****4567",
                                                "tickets": [
                                                  {
                                                    "ticketNumber": "20260901-7KQ4M-9XZ2B",
                                                    "bookingItemId": "f1c0d2e3-2345-6789-abcd-ef0123456789",
                                                    "categoryName": "VIP",
                                                    "holderName": "Tendai Ncube",
                                                    "redeemed": true,
                                                    "redeemedAt": "2026-09-26T09:42:11+02:00",
                                                    "redeemedByName": "Tariro Chikomo"
                                                  },
                                                  {
                                                    "ticketNumber": "20260901-K3D8P-5TV1A",
                                                    "bookingItemId": "0a9b8c7d-6e5f-4a3b-9c2d-1e0f9a8b7c6d",
                                                    "categoryName": "VIP",
                                                    "holderName": "Rufaro Moyo",
                                                    "redeemed": false
                                                  }
                                                ]
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "BOOKING_NOT_FOUND", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "BOOKING_NOT_FOUND",
                                                "confirmationNumber": "INN-20260901-000000"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "BOOKING_NOT_CONFIRMED", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "BOOKING_NOT_CONFIRMED",
                                                "confirmationNumber": "INN-20260901-3C8849"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_ORGANIZER", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "WRONG_ORGANIZER",
                                                "confirmationNumber": "INN-20260901-3C8849"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "NOT_ASSIGNED_TO_EVENT", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "NOT_ASSIGNED_TO_EVENT",
                                                "confirmationNumber": "INN-20260901-3C8849"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "WRONG_EVENT", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Lookup result",
                                              "data": {
                                                "status": "WRONG_EVENT",
                                                "confirmationNumber": "INN-20260901-3C8849"
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "confirmationNumber missing, blank or too long",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "confirmationNumber": "confirmationNumber is required" }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not an EVENT_ORGANIZER, TEAM_MEMBER or CUSTOMER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Forbidden - insufficient role",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "A super-app scanner's access to the event could "
                            + "not be checked. Retryable; nothing was read.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Could not confirm you can scan for this event. Please try again.",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<GateLookupResponseDTO>> lookup(
            @Valid @RequestBody GateLookupRequestDTO request
    ) {
        GateLookupResponseDTO result = gateLookupService.lookup(
                request.getConfirmationNumber(), request.getEventId());
        return ResponseEntity.ok(ApiResult.ok("Lookup result", result));
    }

    /**
     * The redeem row's denormalised display name (booking_items.redeemed_by_name)
     * surfaces on the rejection toast a second scan sees: "already scanned by
     * Tariro Chikomo at 19:42". Prefer the JWT's firstName/lastName claims for
     * that — staff logins are emails like {@code tariro@harare-arena.co.zw}
     * which read as system identifiers, not as a colleague's name, and leak
     * staff addresses across the gate-staff group.
     *
     * <p>The claims are emitted on TEAM_MEMBER and EVENT_ORGANIZER tokens (the
     * scanner roles) by user-service's JwtUtil. Older tokens minted before
     * that change carry no name claims; fall back to the email so the column
     * is never null. Same fallback applies to tokens for staff roles that
     * intentionally don't ship names (defence in depth — a future caller from
     * an unexpected role is still recorded, just by email).
     */
    private static String resolveScannerDisplayName(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getDetails() instanceof JwtAuthDetails d) {
            String first = d.firstName();
            String last = d.lastName();
            boolean hasFirst = first != null && !first.isBlank();
            boolean hasLast = last != null && !last.isBlank();
            if (hasFirst && hasLast) {
                return first + " " + last;
            }
            if (hasFirst) {
                return first;
            }
            if (hasLast) {
                return last;
            }
        }
        return authentication.getName();
    }
}
