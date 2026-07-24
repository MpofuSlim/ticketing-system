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
 *       every seat's QR (the link sent on WhatsApp/SMS).</li>
 * </ul>
 *
 * <p><b>Public</b> (no JWT) — guests must open these from an email/WhatsApp
 * with no app session. Access control is the unguessable booking UUID; tickets
 * are bearer instruments (the link IS the ticket). Only CONFIRMED bookings
 * render — {@link TicketRenderingService} returns empty otherwise → 404.
 * SecurityConfig permits {@code GET /bookings/*&#47;tickets/**}; the path is
 * NOT under {@code /bookings/internal/**} so it routes through the public
 * gateway normally.
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
                    + "CONFIRMED booking — the link sent on WhatsApp/SMS. 404 if not CONFIRMED.")
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

    // The page renders its own image URLs absolute so it works when saved/shared;
    // base URL comes from config (same value the email uses).
    @org.springframework.beans.factory.annotation.Value("${innbucks.tickets.public-base-url:}")
    private String publicBaseUrl;
}
