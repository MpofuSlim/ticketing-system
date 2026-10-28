package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.service.TicketRenderingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Public, customer-facing ticket artifacts that the confirmation email and
 * WhatsApp message link to:
 * <ul>
 *   <li>{@code GET /bookings/{id}/tickets/{ticketNumber}/qr} — the scannable
 *       QR as a PNG (so an email's {@code <img src>} renders reliably, unlike
 *       a stripped data-URI);</li>
 *   <li>{@code GET /bookings/{id}/tickets} — a self-contained HTML page with
 *       every seat's QR (the link in the purchaser's receipt email, and only
 *       there);</li>
 *   <li>{@code GET /bookings/tickets/{itemId}} (and its QR at
 *       {@code .../qr.png}) — ONE ticket, for its holder: the link a named
 *       guest's email carries. Nothing a guest receives links the booking, so
 *       the booking's page is not one step away from a guest's link.</li>
 * </ul>
 *
 * <p><b>Public</b> (no JWT) — guests must open these from an email/WhatsApp
 * with no app session. Access control is the unguessable UUID in the path: the
 * booking's for the purchaser's artifacts, the ticket's own for the holder's.
 * Tickets are bearer instruments (the link IS the ticket). Only CONFIRMED
 * bookings render — {@link TicketRenderingService} returns empty otherwise →
 * 404. SecurityConfig permits {@code GET /bookings/*&#47;tickets/**} for the
 * booking-keyed paths and the three exact {@code /bookings/tickets/...} paths
 * for the holder's; neither covers the other. None is under
 * {@code /bookings/internal/**}, so they route through the public gateway.
 *
 * <p>No class-level {@code @RequestMapping} prefix: the QR endpoint is ALSO
 * served under {@code /brand/bookings/...} (see {@link #ticketQr}), so each
 * method carries its full path.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tickets", description = "Public ticket QR + view page (linked from the confirmation email/WhatsApp).")
public class TicketController {

    private final TicketRenderingService ticketRenderingService;

    // Served at `/qr`, `/qr.png` AND the `/brand/bookings/...` aliases
    // (identical PNG bytes + Content-Type: image/png). The `.png` form is what
    // the WhatsApp e-ticket send uses in qrCodePath, so the media URL Twilio
    // fetches ends in a recognised image extension; `/qr` stays so the email's
    // <img> and any cached links keep working. The `/brand/...` alias exists
    // because the WhatsApp gateway's configured BASE_URL ends in `/brand` (it
    // was provisioned for the brand assets), so the media URLs it builds come
    // out as BASE_URL + /bookings/... = /brand/bookings/... — serving the same
    // PNG there means the gateway config needs no change and the FE can use
    // either form. /brand/** is already public (SecurityConfig) and routed to
    // this service (gateway brand-assets-route); static brand assets are
    // unaffected (controller mappings only claim /brand/bookings/**).
    // Boot 4 / PathPattern does no suffix stripping, so `.png` is a literal
    // path segment (no content-negotiation surprises).
    @GetMapping(
            value = {
                    "/bookings/{bookingId}/tickets/{ticketNumber}/qr",
                    "/bookings/{bookingId}/tickets/{ticketNumber}/qr.png",
                    "/brand/bookings/{bookingId}/tickets/{ticketNumber}/qr",
                    "/brand/bookings/{bookingId}/tickets/{ticketNumber}/qr.png"},
            produces = MediaType.IMAGE_PNG_VALUE)
    @SecurityRequirements({})
    @Operation(summary = "Scannable ticket QR (PNG)",
            description = "Public. Returns the QR image for one ticket of a CONFIRMED booking — the target of "
                    + "the <img> in the confirmation email and the WhatsApp e-ticket. Available at "
                    + "`/qr` and `/qr.png`, and at the same paths under the `/brand` prefix "
                    + "(`/brand/bookings/{bookingId}/tickets/{ticketNumber}/qr.png`) for clients whose "
                    + "configured base URL ends in `/brand`. All forms serve the identical PNG. 404 if the "
                    + "booking isn't CONFIRMED or the ticket number doesn't belong to it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "PNG image", content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE)),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Unknown/again unconfirmed booking or ticket", content = @Content)
    })
    public ResponseEntity<byte[]> ticketQr(@PathVariable UUID bookingId,
                                           @PathVariable String ticketNumber) {
        return ticketRenderingService.ticketQrPng(bookingId, ticketNumber)
                .map(png -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate())
                        .body(png))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping(value = "/bookings/{bookingId}/tickets", produces = MediaType.TEXT_HTML_VALUE)
    @SecurityRequirements({})
    @Operation(summary = "Ticket view page (HTML)",
            description = "Public. A self-contained page rendering every seat's QR + ticket number for a "
                    + "CONFIRMED booking — the link in the purchaser's receipt email. It shows every ticket, so it "
                    + "is never sent to a named guest (a guest gets `/bookings/tickets/{itemId}`). 404 if not "
                    + "CONFIRMED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "HTML page", content = @Content(mediaType = MediaType.TEXT_HTML_VALUE)),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Unknown or unconfirmed booking", content = @Content)
    })
    public ResponseEntity<String> ticketPage(@PathVariable UUID bookingId) {
        return ticketRenderingService.ticketPageHtml(bookingId, publicBaseUrl)
                .map(html -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .cacheControl(CacheControl.noCache())
                        .body(html))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // ONE ticket, for its holder: what a named guest's email links to. Keyed by
    // the ticket's own row id with no booking id in the path, because the
    // booking's page above shows every guest's QR, and a guest who could reach
    // it could enter on someone else's ticket. Its QR is served by the same id
    // for the same reason. SecurityConfig permits exactly these three paths.
    //
    // The id is pinned to a canonical UUID in the pattern. Unpinned,
    // `/bookings/tickets/{itemId}` and `/bookings/{bookingId}/tickets` are
    // equally specific, so `/bookings/tickets/tickets` matched both and was a
    // 500 ("Ambiguous handler methods") any anonymous caller could produce.
    private static final String ITEM_ID =
            "{itemId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    @GetMapping(value = "/bookings/tickets/" + ITEM_ID, produces = MediaType.TEXT_HTML_VALUE)
    @SecurityRequirements({})
    @Operation(summary = "One ticket's page, for its holder (HTML)",
            description = "Public. A self-contained page with ONE ticket of a CONFIRMED booking: its QR, who it "
                    + "is for, its type and seat, and its number. Nothing else of the booking (no reference, no "
                    + "total, no other tickets). The link sent to a named guest. `itemId` is the ticket's own "
                    + "row id, carried in that link (no customer-facing response returns it); it is neither the "
                    + "booking's id nor the ticket number. 404 if the id is not a UUID, the ticket is unknown, "
                    + "or its booking isn't CONFIRMED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "HTML page", content = @Content(mediaType = MediaType.TEXT_HTML_VALUE)),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a UUID, unknown ticket, or its booking isn't CONFIRMED", content = @Content)
    })
    public ResponseEntity<String> holderTicketPage(@PathVariable UUID itemId) {
        return ticketRenderingService.holderTicketPageHtml(itemId, publicBaseUrl)
                .map(html -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .cacheControl(CacheControl.noCache())
                        .body(html))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping(
            value = {"/bookings/tickets/" + ITEM_ID + "/qr", "/bookings/tickets/" + ITEM_ID + "/qr.png"},
            produces = MediaType.IMAGE_PNG_VALUE)
    @SecurityRequirements({})
    @Operation(summary = "One ticket's QR, by the ticket's id (PNG)",
            description = "Public. The QR image on the holder's page (`/bookings/tickets/{itemId}`), addressed by "
                    + "the ticket's id so the page never names the booking. Served at `/qr` and `/qr.png`. 404 "
                    + "if the id is not a UUID, the ticket is unknown, or its booking isn't CONFIRMED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "PNG image", content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE)),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a UUID, unknown ticket, or its booking isn't CONFIRMED", content = @Content)
    })
    public ResponseEntity<byte[]> holderTicketQr(@PathVariable UUID itemId) {
        return ticketRenderingService.holderTicketQrPng(itemId)
                .map(png -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate())
                        .body(png))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // The page renders its own image URLs absolute so it works when saved/shared;
    // base URL comes from config (same value the email uses).
    @org.springframework.beans.factory.annotation.Value("${innbucks.tickets.public-base-url:}")
    private String publicBaseUrl;
}
