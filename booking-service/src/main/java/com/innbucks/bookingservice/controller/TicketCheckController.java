package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.config.BookingMetrics.TicketCheckOutcome;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TicketCheckRequestDTO;
import com.innbucks.bookingservice.dto.TicketCheckResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.util.MsisdnValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * The partner yes/no ticket check: "does this phone hold a CONFIRMED ticket for
 * this event?". Built for the super app's group-chat function, so it can stop
 * reading the public ticket wallet ({@code GET /bookings/public/phone/{phone}})
 * that returned every ticket of a number with its QR codes.
 *
 * <p>It answers yes or no and nothing else: no ticket numbers, no QR, no count.
 * The caller is a server holding its own key ({@code X-Api-Key},
 * {@code app.booking.ticket-check-api-key}). The key is checked before the body
 * is read, so a caller without it never learns which field was wrong. Blank key
 * = the endpoint is off (404). The gateway's {@code booking-ticket-check-route}
 * keys its limiter on the caller's IP and fails safe.
 */
@RestController
@RequestMapping("/bookings")
@Slf4j
@Tag(name = "Ticket check (partners)",
        description = "A yes/no answer for a partner backend holding its own API key.")
public class TicketCheckController {

    static final String API_KEY_HEADER = "X-Api-Key";

    private final BookingRepository bookingRepository;
    private final BookingMetrics metrics;
    private final String apiKey;
    private final String deploymentCountry;

    public TicketCheckController(BookingRepository bookingRepository,
                                 BookingMetrics metrics,
                                 @Value("${app.booking.ticket-check-api-key:}") String apiKey,
                                 @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.bookingRepository = bookingRepository;
        this.metrics = metrics;
        this.apiKey = apiKey;
        this.deploymentCountry = deploymentCountry;
    }

    @PostMapping("/ticket-check")
    @SecurityRequirements({})
    @Operation(summary = "Does this phone hold a ticket for this event? (partner key)",
            description = """
                    Answers `hasTicket: true` when the phone holds at least one CONFIRMED ticket for \
                    the event: as the purchaser, or as a named guest the purchaser gave their own phone. \
                    PENDING (unpaid) and CANCELLED bookings never count. An unknown event or phone is \
                    simply `false`.

                    **Auth:** the `X-Api-Key` header, a key DTX issues to the partner. It is checked \
                    before the body is read. A missing or wrong key is one `401` whatever the body \
                    holds. When no key is configured on the cell, the endpoint answers `404`.

                    **The phone goes in the body**, never the URL, so it does not reach access logs. \
                    Any common spelling works (`+263771234567`, `0771234567`).

                    Rate-limited per caller IP at the gateway (`429` when exceeded).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Answered",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Holds a ticket", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Ticket check completed",
                                              "data": { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "hasTicket": true }
                                            }"""),
                                    @ExampleObject(name = "No ticket", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Ticket check completed",
                                              "data": { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "hasTicket": false }
                                            }""")})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Key accepted, but the phone or event id is missing or malformed",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Bad phone", value = """
                                            { "code": "400 BAD_REQUEST", "message": "phoneNumber is not a valid phone number.", "data": null }"""),
                                    @ExampleObject(name = "Bad event id", value = """
                                            { "code": "400 BAD_REQUEST", "message": "eventId must be a UUID.", "data": null }""")})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or wrong X-Api-Key",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Missing or invalid X-Api-Key", "data": null }"""))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "The ticket check is not switched on for this cell (no key configured)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Not found", "data": null }""")))
    })
    public ResponseEntity<ApiResult<TicketCheckResponseDTO>> check(
            @RequestHeader(value = API_KEY_HEADER, required = false) String presentedKey,
            @RequestBody(required = false) TicketCheckRequestDTO body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new NotFoundException("Not found");
        }
        if (!keyMatches(presentedKey)) {
            metrics.ticketCheck(TicketCheckOutcome.REJECTED);
            log.warn("Ticket check refused: missing or wrong {}", API_KEY_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<TicketCheckResponseDTO>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Missing or invalid X-Api-Key")
                            .data(null)
                            .build());
        }

        String phone = requirePhone(body == null ? null : body.phoneNumber());
        UUID eventId = requireEventId(body == null ? null : body.eventId());

        boolean held = bookingRepository.countHeldBy(eventId, phone, Booking.BookingStatus.CONFIRMED) > 0;
        metrics.ticketCheck(held ? TicketCheckOutcome.HELD : TicketCheckOutcome.NOT_HELD);
        log.info("Ticket check eventId={} phone={} hasTicket={}", eventId, MsisdnMasking.mask(phone), held);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("Ticket check completed", new TicketCheckResponseDTO(eventId, held)));
    }

    private boolean keyMatches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** E.164, the form bookings and attendee phones are stored in. */
    private String requirePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            metrics.ticketCheck(TicketCheckOutcome.INVALID);
            throw new BadRequestException("phoneNumber is required.");
        }
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry).orElseThrow(() -> {
            metrics.ticketCheck(TicketCheckOutcome.INVALID);
            return new BadRequestException("phoneNumber is not a valid phone number.");
        });
    }

    private UUID requireEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            metrics.ticketCheck(TicketCheckOutcome.INVALID);
            throw new BadRequestException("eventId is required.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            metrics.ticketCheck(TicketCheckOutcome.INVALID);
            throw new BadRequestException("eventId must be a UUID.");
        }
    }
}
