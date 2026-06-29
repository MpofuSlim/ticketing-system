package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.ExtendHoldRequestDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
import com.innbucks.bookingservice.dto.ConfirmBookingRequestDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.PublicBookingResponseDTO;
import com.innbucks.bookingservice.dto.EventActiveCountDTO;
import com.innbucks.bookingservice.dto.CategoryActiveCountDTO;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.security.MinTier;
import com.innbucks.bookingservice.security.TierAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import com.innbucks.bookingservice.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Bookings", description = "Create, query, confirm, and cancel bookings.")
public class BookingController {

    private final BookingService bookingService;
    private final com.innbucks.bookingservice.client.UserServiceClient userServiceClient;
    private final com.innbucks.bookingservice.service.EventChangeNotificationService eventChangeNotificationService;

    /**
     * Default tier applied to a booking when the caller's phone isn't
     * registered with user-service, or user-service is unreachable. Matches
     * the existing minimum bookable rung (2 = max 10 seats per booking), so
     * a guest can book up to ten seats without registering first. A
     * registered customer with a higher real tier (3 or 4) gets their actual
     * tier whenever user-service answers, unlocking the larger caps.
     */
    private static final int GUEST_TIER = 2;

    /**
     * Shared secret payment-service must present on PATCH
     * /bookings/{id}/confirm. Booking confirm is service-to-service only —
     * it's called from payment-service after a successful payment to flip
     * PENDING -> CONFIRMED + trigger loyalty earn/redeem. Previously
     * permitAll, so anyone with a UUID could confirm someone else's
     * booking, burning their loyalty points before any payment landed.
     */
    @Value("${innbucks.internal-api-token}")
    private String expectedInternalToken;

    /**
     * Cell country (ISO 3166-1 alpha-2), used as the default region when
     * validating a customer-supplied phone number that lacks a {@code +}
     * country code. Same pin the rest of the service uses.
     */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

    @PostMapping
    @Operation(summary = "Create booking", description = "Creates a new pending booking. The customer's tier is resolved from user-service via the phone number — JWT phone wins when present, otherwise the request body's `phoneNumber`. " +
            "Returns 404 if the phone number is not registered in user-service. Per-tier seat-count limits in BookingService still apply.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Booking created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking created", value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Booking created successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "PENDING",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:45:00",
                                        "expiresAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Customer tier below minimum. Envelope: { code: '422', message: <reason>, data: { requiredTier, currentTier } }"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or domain error")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> createBooking(
            @Valid @RequestBody CreateBookingRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());

        String phoneNumber = authenticated ? extractPhoneNumber(authentication) : null;
        if (phoneNumber == null || phoneNumber.isBlank()) {
            phoneNumber = request.getPhoneNumber();
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new BadRequestException("Please provide your phone number.");
        }
        // Validate + canonicalise to E.164 before it's stored and later sent to
        // WhatsApp. A malformed number (wrong length for its country, stray
        // characters) otherwise sails through to Twilio and fails delivery with
        // error 63024 ("invalid recipient") long after the booking is created —
        // a silent no-ticket. Reject it here with an actionable 400 instead, and
        // store the normalised +E.164 form so downstream always has a clean value.
        String normalizedPhone = com.innbucks.bookingservice.util.MsisdnValidator
                .normalizeToE164(phoneNumber, deploymentCountry)
                .orElseThrow(() -> new BadRequestException(
                        "That phone number doesn't look valid. Please enter it in full "
                                + "international format, e.g. +263772000000."));
        phoneNumber = normalizedPhone;

        // Look up the customer's current tier in user-service — but ONLY for
        // AUTHENTICATED callers. An anonymous (phone-only) booking is a guest
        // by definition: the phone is client-supplied and, for a true guest,
        // will not resolve to a registered customer, so the lookup is a doomed
        // round trip we already know the answer to (GUEST_TIER). Worse, on the
        // booking hot path it's one user-service call PER guest booking —
        // enough, under load, to saturate user-service and trip this client's
        // circuit breaker. Skipping it keeps user-service off the guest path
        // entirely (mirrors the event-service tenant-lookup skip in #168).
        //
        // For an authenticated caller we still look up the LIVE tier: the JWT's
        // tier claim goes stale after an upgrade, and a tier 3/4 customer needs
        // their real tier to unlock the larger per-booking seat caps. A null
        // result there still falls back to GUEST_TIER and covers both the
        // "phone not registered" and "user-service unreachable / circuit open"
        // cases. Guests are never blocked either way — the registered-only
        // requirement is on RETRIEVAL (the GET endpoints), not creation.
        com.innbucks.bookingservice.dto.CustomerTierResponseDTO tierData = null;
        if (authenticated) {
            try {
                com.innbucks.bookingservice.dto.ApiResult<com.innbucks.bookingservice.dto.CustomerTierResponseDTO> result =
                        userServiceClient.getCustomerTier(phoneNumber);
                tierData = result == null ? null : result.getData();
            } catch (Exception ex) {
                log.warn("user-service tier lookup failed phoneNumber={} cause={}", MsisdnMasking.mask(phoneNumber), ex.toString());
                // tierData stays null (its initial value) -> GUEST_TIER fallback below.
            }
        }
        int tier;
        String tierEmail;
        if (tierData == null) {
            tier = GUEST_TIER;
            tierEmail = null;
            log.info("Guest booking path phoneNumber={} defaultTier={}",
                    MsisdnMasking.mask(phoneNumber), tier);
        } else {
            tier = tierData.getCurrentTier();
            tierEmail = tierData.getEmail();
        }

        String userEmail = authenticated ? authentication.getName() : request.getUserEmail();
        if (userEmail == null || userEmail.isBlank()) {
            userEmail = tierEmail;
        }
        if (userEmail != null && userEmail.isBlank()) {
            userEmail = null;
        }
        log.info("POST /bookings userEmail={} tier={} phoneNumber={} eventId={} seats={}",
                userEmail, tier, MsisdnMasking.mask(phoneNumber), request.getEventId(), request.getSeats().size());
        BookingResponseDTO created = bookingService.createBooking(userEmail, tier, phoneNumber, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Booking created successfully", created));
    }

    private String extractPhoneNumber(Authentication authentication) {
        Object details = authentication.getDetails();
        return details instanceof JwtAuthDetails d ? d.phoneNumber() : null;
    }

    // Tier is stamped on the request by TierAccessInterceptor after a live
    // user-service lookup, so the JWT's (potentially stale) tier claim never
    // feeds the per-tier seat-count check.
    private int currentTier(HttpServletRequest request) {
        Object value = request.getAttribute(TierAccessInterceptor.CURRENT_TIER_ATTRIBUTE);
        return value instanceof Integer i ? i : 0;
    }

    @GetMapping("/my")
    @Operation(summary = "List my bookings", description = "Returns all bookings for the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "My bookings", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
                                        {
                                          "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                          "userEmail": "alice@example.com",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "confirmationNumber": "INN-20260502-AB12CD",
                                          "status": "CONFIRMED",
                                          "totalAmount": 200.00,
                                          "items": [
                                            {
                                              "seatId": "11111111-2222-3333-4444-555555555555",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 12,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12345A",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            },
                                            {
                                              "seatId": "22222222-3333-4444-5555-666666666666",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 13,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12346B",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            }
                                          ],
                                          "createdAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:50:00"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT")
    })
    public ResponseEntity<ApiResult<List<BookingResponseDTO>>> getMyBookings(Authentication authentication) {
        String userEmail = authentication.getName();
        log.debug("GET /bookings/my userEmail={}", userEmail);
        List<BookingResponseDTO> result = bookingService.getMyBookings(userEmail);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully", result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get booking by id", description = "Returns a specific booking if it belongs to the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking by id", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking retrieved successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> getBookingById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/{} userEmail={} isAdmin={}", id, userEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getBookingById(id, userEmail, isAdmin)));
    }

    /**
     * Public lookup by id — same response shape as {@link #getBookingById}
     * above, but no JWT required. The UUID is the bearer credential, mirroring
     * the existing public {@code GET /bookings/confirmation/{number}} and the
     * hosted ticket-QR endpoint: anyone who knows the id can view the booking.
     * Use case: a customer (or guest) following a magic link from the
     * confirmation email/WhatsApp who isn't logged in.
     *
     * <p>The authenticated {@code GET /bookings/{id}} above stays unchanged
     * (still ownership-scoped). Distinct path so the access model is explicit
     * in the URL.
     */
    @GetMapping("/public/{id}")
    @SecurityRequirements({})
    @Operation(summary = "Get booking by id (public, PII-free)",
            description = "Public lookup of a booking by UUID. No authentication required — access "
                    + "control is the unguessable UUID, same model as the existing public lookup by "
                    + "confirmation number. Returns a TRIMMED, PII-free view (PublicBookingResponseDTO): "
                    + "no userEmail / phoneNumber / tenantId / payment-split. Use the authenticated "
                    + "GET /bookings/{id} for the full owner view. 404 if the id is unknown.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Booking returned (trimmed view)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PublicBookingResponseDTO.class),
                            examples = @ExampleObject(name = "Public lookup by id", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking retrieved successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "expiresAt": null
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Booking not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PublicBookingResponseDTO>> getBookingByIdPublic(@PathVariable UUID id) {
        log.debug("GET /bookings/public/{} (public lookup)", id);
        // no-store: this is the FE's post-payment polling target and its body
        // transitions PENDING -> CONFIRMED as the InnBucks code is approved. As
        // an unauthenticated GET keyed only by URL it is trivially cacheable by
        // a CDN/proxy/browser; a cached PENDING snapshot would strand the
        // customer on "awaiting confirmation" even after the booking is
        // CONFIRMED (the "only updates when I refresh" symptom). Force every
        // poll to reach the service.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("Booking retrieved successfully",
                        bookingService.getBookingByIdPublic(id)));
    }

    /**
     * Service-to-service read of a booking by id — used by payment-service to
     * resolve {@code totalAmount} + {@code phoneNumber} before issuing an
     * InnBucks payment code. Distinct from the customer {@code GET /{id}}
     * above, which scopes by the JWT's owner: this path carries NO customer
     * JWT (it must work for GUEST bookings too), so ownership is not checked —
     * the trust boundary is the shared {@code X-Internal-Token}.
     *
     * <p>"Internal endpoints — three files agree": controller checks the token
     * (here), {@code SecurityConfig} permits {@code GET /bookings/internal/**}
     * (so Spring Security doesn't 401 before this runs), and the gateway's
     * {@code booking-internal-deny} route blocks the path at the edge.
     */
    @GetMapping("/internal/{id}")
    @SecurityRequirements()
    @Operation(summary = "Get booking by id (internal S2S)",
            description = "Service-to-service read for payment-service — resolves a booking's "
                    + "totalAmount + phoneNumber before issuing an InnBucks payment code, and works for "
                    + "guest bookings (no customer JWT). Authenticated with the shared X-Internal-Token; "
                    + "ownership is not checked; denied at the gateway edge.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Booking returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> getBookingByIdInternal(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /bookings/internal/{} — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<BookingResponseDTO>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Missing or invalid X-Internal-Token")
                            .data(null)
                            .build());
        }
        log.info("GET /bookings/internal/{} (S2S)", id);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getBookingById(id, null, true)));
    }

    /**
     * S2S: payment-service extends the seat hold to outlive the InnBucks
     * payment code it is about to mint (hold 5 min vs code 10 min was the
     * paid-but-no-ticket gap). Same internal-token discipline as the GET
     * above; 409 when the booking is no longer PENDING or the hold already
     * lapsed — the caller refuses the payment before any money moves.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/internal/{id}/extend-hold")
    @SecurityRequirements()
    @Operation(summary = "Extend seat hold (internal S2S)",
            description = "payment-service calls this before minting a payment code so the hold provably "
                    + "outlives the code. Never shortens a hold. Authenticated with the shared "
                    + "X-Internal-Token; denied at the gateway edge.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Hold extended (or already long enough)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Booking not PENDING or hold already expired")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> extendHoldInternal(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @Valid @RequestBody ExtendHoldRequestDTO request
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized PATCH /bookings/internal/{}/extend-hold — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<BookingResponseDTO>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Missing or invalid X-Internal-Token")
                            .data(null)
                            .build());
        }
        if (request == null || request.getHoldUntil() == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.<BookingResponseDTO>builder()
                            .code("400 BAD_REQUEST")
                            .message("holdUntil is required")
                            .data(null)
                            .build());
        }
        log.info("PATCH /bookings/internal/{}/extend-hold holdUntil={} (S2S)", id, request.getHoldUntil());
        return ResponseEntity.ok(ApiResult.ok("Seat hold extended",
                bookingService.extendHold(id, request.getHoldUntil())));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }

    @GetMapping("/by-category/{categoryId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List bookings by seat category",
            description = "Analytics endpoint. Returns one row per booked seat in the given category, " +
                    "including who bought it and when. Includes CANCELLED bookings — filter client-side if you need " +
                    "to exclude them. Restricted to EVENT_ORGANIZER because it exposes customer emails."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryBookingDTO.class),
                            examples = @ExampleObject(name = "Bookings by category", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
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
                                          "ticketNumber": "20260502-12345A",
                                          "priceAtBooking": 100.00,
                                          "bookedAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:45:00",
                                          "expiresAt": null
                                        },
                                        {
                                          "bookingId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "userEmail": "bob@example.com",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "status": "CANCELLED",
                                          "confirmationNumber": "INN-20260501-EF34GH",
                                          "seatId": "22222222-3333-4444-5555-666666666666",
                                          "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                          "categoryName": "VIP",
                                          "rowLabel": "A",
                                          "seatNumber": 13,
                                          "ticketNumber": "20260501-67890B",
                                          "priceAtBooking": 100.00,
                                          "bookedAt": "2026-05-01T10:30:00",
                                          "updatedAt": "2026-05-01T11:00:00",
                                          "expiresAt": null
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByCategory(
            @PathVariable UUID categoryId,
            Authentication authentication) {
        UUID requesterOrganizerUuid = com.innbucks.bookingservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/by-category/{} requesterOrganizerUuid={} isAdmin={}",
                categoryId, requesterOrganizerUuid, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByCategory(categoryId, requesterOrganizerUuid, isAdmin)));
    }

    @GetMapping("/by-event/{eventId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List bookings by event",
            description = "Analytics endpoint. Returns one row per booked seat across every category " +
                    "in the given event. Includes CANCELLED bookings — caller filters. " +
                    "Used by seat-service to build event-level analytics in a single round-trip. " +
                    "Restricted to EVENT_ORGANIZER because it exposes customer emails."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryBookingDTO.class),
                            examples = @ExampleObject(name = "Bookings by event", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
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
                                          "ticketNumber": "20260502-12345A",
                                          "priceAtBooking": 100.00,
                                          "bookedAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:45:00",
                                          "expiresAt": null
                                        },
                                        {
                                          "bookingId": "c5d1e3f4-3456-7890-abcd-ef0123456789",
                                          "userEmail": "carol@example.com",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "status": "PENDING",
                                          "confirmationNumber": "INN-20260502-IJ56KL",
                                          "seatId": "33333333-4444-5555-6666-777777777777",
                                          "categoryId": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22",
                                          "categoryName": "GA",
                                          "rowLabel": "F",
                                          "seatNumber": 4,
                                          "ticketNumber": "20260502-99999C",
                                          "priceAtBooking": 60.00,
                                          "bookedAt": "2026-05-02T14:00:00",
                                          "updatedAt": "2026-05-02T14:00:00",
                                          "expiresAt": "2026-05-02T14:05:00"
                                        }
                                      ]
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Authenticated but not EVENT_ORGANIZER")
    })
    public ResponseEntity<ApiResult<List<CategoryBookingDTO>>> getBookingsByEvent(
            @PathVariable UUID eventId,
            Authentication authentication) {
        UUID requesterOrganizerUuid = com.innbucks.bookingservice.security.AuthenticatedCaller
                .organizerUuid(authentication);
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.debug("GET /bookings/by-event/{} requesterOrganizerUuid={} isAdmin={}",
                eventId, requesterOrganizerUuid, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getBookingsByEvent(eventId, requesterOrganizerUuid, isAdmin)));
    }

    @GetMapping("/active-counts")
    @SecurityRequirements()
    @Operation(
            summary = "Active booking item counts per event",
            description = "Internal endpoint used by event-service to compute `availableTickets` " +
                    "(`totalCapacity − count`). Returns one row per supplied eventId that has at least " +
                    "one PENDING or CONFIRMED booking item; eventIds with no active bookings are absent " +
                    "from the response. CANCELLED bookings are excluded so released seats free up capacity."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Counts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EventActiveCountDTO.class),
                            examples = @ExampleObject(name = "Active counts", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active booking counts retrieved successfully",
                                      "data": [
                                        { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "count": 42 },
                                        { "eventId": "9b1c5d2e-8f3a-4b1c-a4d2-1e2c5d4f8a3b", "count": 7 }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<EventActiveCountDTO>>> getActiveCounts(
            @RequestParam("eventIds") List<UUID> eventIds) {
        log.debug("GET /bookings/active-counts eventIds={}", eventIds);
        return ResponseEntity.ok(ApiResult.ok("Active booking counts retrieved successfully",
                bookingService.getActiveItemCountsByEvents(eventIds)));
    }

    @GetMapping("/categories/active-counts")
    @SecurityRequirements()
    @Operation(
            summary = "Active booking item counts per category",
            description = "Internal endpoint used by seat-service to compute a live `availableSeats` " +
                    "per seat category (`totalSeats − count`). Returns one row per supplied categoryId " +
                    "that has at least one PENDING or CONFIRMED booking item; categoryIds with no active " +
                    "bookings are absent from the response (the caller reads them as full capacity). " +
                    "CANCELLED bookings are excluded so released holds free capacity immediately."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Counts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CategoryActiveCountDTO.class),
                            examples = @ExampleObject(name = "Active category counts", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Active category counts retrieved successfully",
                                      "data": [
                                        { "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11", "count": 37 },
                                        { "categoryId": "9e2c5b4f-2d1f-4a28-8b1c-2e5c8a7b6d22", "count": 12 }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<CategoryActiveCountDTO>>> getCategoryActiveCounts(
            @RequestParam("categoryIds") List<UUID> categoryIds) {
        log.debug("GET /bookings/categories/active-counts categoryIds={}", categoryIds);
        return ResponseEntity.ok(ApiResult.ok("Active category counts retrieved successfully",
                bookingService.getActiveItemCountsByCategories(categoryIds)));
    }

    @GetMapping("/confirmation/{number}")
    @SecurityRequirements()
    @Operation(summary = "Lookup by confirmation number", description = "Public endpoint used to verify a booking by confirmation number.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking by confirmation number", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking retrieved successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Confirmation number not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> getByConfirmationNumber(@PathVariable String number) {
        log.debug("GET /bookings/confirmation/{}", number);
        return ResponseEntity.ok(ApiResult.ok("Booking retrieved successfully",
                bookingService.getByConfirmationNumber(number)));
    }

    @GetMapping("/phone/{phoneNumber}")
    @SecurityRequirements()
    @Operation(
            summary = "Lookup bookings by phone number",
            description = "Public endpoint. Returns the CONFIRMED bookings attached to the given phone " +
                    "number, most recent first — i.e. the customer's paid, valid tickets. PENDING " +
                    "(awaiting-payment) and CANCELLED bookings are excluded, so a booking only appears " +
                    "here once payment is confirmed. Returns an empty list if no confirmed bookings " +
                    "exist for that phone. To track an in-flight booking through payment, poll " +
                    "GET /bookings/public/{id} instead — that endpoint surfaces the PENDING booking " +
                    "and flips to CONFIRMED when the InnBucks code is approved."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Bookings returned (may be empty)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Bookings by phone", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Bookings retrieved successfully",
                                      "data": [
                                        {
                                          "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                          "userEmail": "alice@example.com",
                                          "phoneNumber": "+254700000000",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                          "confirmationNumber": "INN-20260502-AB12CD",
                                          "status": "CONFIRMED",
                                          "totalAmount": 100.00,
                                          "items": [
                                            {
                                              "seatId": "11111111-2222-3333-4444-555555555555",
                                              "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                              "categoryName": "VIP",
                                              "rowLabel": "A",
                                              "seatNumber": 12,
                                              "priceAtBooking": 100.00,
                                              "ticketNumber": "20260502-12345A",
                                              "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                            }
                                          ],
                                          "createdAt": "2026-05-02T15:45:00",
                                          "updatedAt": "2026-05-02T15:50:00",
                                          "expiresAt": null
                                        }
                                      ]
                                    }
                                    """)
                    )
            )
    })
    public ResponseEntity<ApiResult<List<BookingResponseDTO>>> getBookingsByPhoneNumber(
            @PathVariable String phoneNumber) {
        log.debug("GET /bookings/phone/{}", MsisdnMasking.mask(phoneNumber));
        return ResponseEntity.ok(ApiResult.ok("Bookings retrieved successfully",
                bookingService.getActiveByPhoneNumber(phoneNumber)));
    }

    @PatchMapping("/{id}/cancel")
    @MinTier(2)
    @Operation(summary = "Cancel booking", description = "Cancels a booking before payment confirmation. Requires tier 2.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking cancelled",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking cancelled", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking cancelled successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CANCELLED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T16:00:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Cannot cancel in current state")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> cancelBooking(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String userEmail = authentication.getName();
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        log.info("PATCH /bookings/{}/cancel userEmail={} isAdmin={}", id, userEmail, isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Booking cancelled successfully",
                bookingService.cancelBooking(id, userEmail, isAdmin)));
    }

    @PatchMapping("/{id}/reverse")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Reverse a confirmed booking (SUPER_ADMIN only)",
            description = """
                    Reverses a **CONFIRMED** booking — admin refund, no-show, or
                    (once veengu real payment integration arrives) a money-transfer
                    failure that arrives after the booking was already CONFIRMED.

                    Calls event-service to restore the booking's consumed seats to
                    the available pool, then flips the booking to **CANCELLED**.
                    Per-booking idempotent: a successful release flips
                    `availability_released=true` on the booking row so a retried
                    reversal short-circuits the release call and never double-credits.
                    If the release call fails (event-service unreachable mid-call),
                    the booking is NOT marked CANCELLED — the admin retries the
                    same call until release succeeds.

                    Distinct from `PATCH /bookings/{id}/cancel`: cancel is for
                    PENDING (no payment processed); reverse is for CONFIRMED.

                    Requires **SUPER_ADMIN** role.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking reversed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking reversed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking reversed successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CANCELLED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T16:30:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Booking not CONFIRMED, or event-service release failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing/invalid JWT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> reverseBooking(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String adminEmail = authentication.getName();
        log.info("PATCH /bookings/{}/reverse adminEmail={}", id, adminEmail);
        return ResponseEntity.ok(ApiResult.ok("Booking reversed successfully",
                bookingService.reverseConfirmedBooking(id, adminEmail)));
    }

    @PatchMapping("/internal/{id}/confirm")
    @Operation(summary = "Confirm booking (internal S2S)",
            description = "Marks a booking as confirmed — called by payment-service when a " +
                          "payment lands. Moved under /bookings/internal/** so the gateway's " +
                          "edge-deny rule blocks any public attempt (defence-in-depth on top " +
                          "of the X-Internal-Token check below).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Booking confirmed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponseDTO.class),
                            examples = @ExampleObject(name = "Booking confirmed", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Booking confirmed successfully",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "userEmail": "alice@example.com",
                                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "status": "CONFIRMED",
                                        "totalAmount": 100.00,
                                        "items": [
                                          {
                                            "seatId": "11111111-2222-3333-4444-555555555555",
                                            "categoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryName": "VIP",
                                            "rowLabel": "A",
                                            "seatNumber": 12,
                                            "priceAtBooking": 100.00,
                                            "ticketNumber": "20260502-12345A",
                                            "qrCode": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAQAAACX...(truncated)"
                                          }
                                        ],
                                        "createdAt": "2026-05-02T15:45:00",
                                        "updatedAt": "2026-05-02T15:50:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Concurrent confirm — another caller won the race; the booking is already CONFIRMED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid booking state")
    })
    public ResponseEntity<ApiResult<BookingResponseDTO>> confirmBooking(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @Valid @RequestBody(required = false) ConfirmBookingRequestDTO request
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized PATCH /bookings/{}/confirm — missing or wrong X-Internal-Token", id);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<BookingResponseDTO>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Missing or invalid X-Internal-Token")
                            .data(null)
                            .build());
        }
        log.info("PATCH /bookings/{}/confirm pointsToUse={} cashAmount={}", id,
                request == null ? null : request.getPointsToUse(),
                request == null ? null : request.getCashAmount());
        try {
            return ResponseEntity.ok(ApiResult.ok("Booking confirmed successfully",
                    bookingService.confirmBooking(id, request)));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                | jakarta.persistence.OptimisticLockException ex) {
            // Two confirms raced; the loser sees zero-row UPDATE. Surface as
            // 409 so the caller (payment-service) knows the booking has
            // already been confirmed by the other side of the race — same
            // resolution as the optimistic-replay case in our idempotency
            // contract.
            log.warn("Optimistic-lock collision on PATCH /bookings/{}/confirm — booking already confirmed", id);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResult.<BookingResponseDTO>builder()
                            .code("409 CONFLICT")
                            .message("Booking already confirmed by a concurrent request")
                            .data(null)
                            .build());
        }
    }

    @PostMapping("/internal/events/{eventId}/change-notification")
    @Operation(summary = "Notify confirmed attendees of an event change/cancel (internal)",
            description = "Service-to-service only — event-service calls this after an organizer changes "
                    + "an event's time/venue or cancels it. Fans the notification out to the event's "
                    + "CONFIRMED attendees (SMS primary, WhatsApp fallback) asynchronously and returns 202. "
                    + "Authenticated with the shared X-Internal-Token; denied at the gateway edge.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "Broadcast accepted; attendees are notified asynchronously"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token")
    })
    public ResponseEntity<ApiResult<Void>> notifyEventChange(
            @PathVariable UUID eventId,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @RequestBody(required = false) com.innbucks.bookingservice.dto.EventChangeNotificationRequest request
    ) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized POST /bookings/internal/events/{}/change-notification — bad X-Internal-Token",
                    eventId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<Void>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Missing or invalid X-Internal-Token")
                            .data(null)
                            .build());
        }
        log.info("POST /bookings/internal/events/{}/change-notification changeType={}",
                eventId, request == null ? null : request.changeType());
        eventChangeNotificationService.broadcast(
                eventId,
                request == null ? null : request.changeType(),
                request == null ? null : request.eventTitle(),
                request == null ? null : request.newStartDateTime(),
                request == null ? null : request.newVenue());
        return ResponseEntity.accepted()
                .body(ApiResult.<Void>builder()
                        .code("202 ACCEPTED")
                        .message("Event-change notification accepted")
                        .data(null)
                        .build());
    }

    /**
     * Constant-time shared-secret check for the internal confirm endpoint.
     * Mirrors loyalty-service's InternalMerchantLookupController and
     * event-service's consumeAvailability — {@code MessageDigest.isEqual}
     * so an attacker can't derive the token byte-by-byte from response-time
     * differences.
     */
    private boolean authorizedInternal(String presented) {
        if (expectedInternalToken == null || expectedInternalToken.isBlank()) {
            log.warn("innbucks.internal-api-token is not configured; rejecting internal call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedInternalToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
