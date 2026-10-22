package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.promo.CreatePromoCodeRequestDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeCheckDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeCheckRequestDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeTermsDTO;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.PromoCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

/**
 * Promo codes (V27). The organizer creates, lists and edits an event's codes;
 * the checkout checks one before booking; {@code POST /bookings} applies it
 * (its {@code promoCode} field). Rides the gateway's {@code /bookings/**} route.
 */
@Slf4j
@RestController
@RequestMapping("/bookings/promo-codes")
@RequiredArgsConstructor
@Tag(name = "Promo codes", description = "Discount codes an organizer creates for an event")
public class PromoCodeController {

    private static final String CODE_EXAMPLE = """
            {
              "id": "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e",
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "code": "EARLY10",
              "description": "10% off early tickets",
              "discountType": "PERCENT",
              "discountValue": 10.00,
              "maxUses": 100,
              "maxUsesPerCustomer": 1,
              "startsAt": null,
              "endsAt": "2026-09-08T23:59:59+02:00",
              "categoryIds": [],
              "active": true,
              "uses": 37,
              "discountGiven": 555.00,
              "createdAt": "2026-08-25T08:00:00+02:00"
            }""";

    private final PromoCodeService promoCodeService;

    @PostMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Create a promo code",
            description = "Creates a code for one event the caller owns (SUPER_ADMIN: any event). PERCENT takes "
                    + "1-99 % off each eligible ticket; FIXED takes an amount off each. The discount comes off "
                    + "the ticket's price tier, if any. A booking that would make a ticket free is refused. "
                    + "`code` is stored in upper case and matched in any case; it cannot be changed later. "
                    + "`startsAt` / `endsAt` are the organizer's local time with no zone. `categoryIds` empty = "
                    + "every category of the event.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Created",
                            value = "{ \"code\": \"201 CREATED\", \"message\": \"Promo code created\", \"data\": "
                                    + "{ \"id\": \"1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e\", \"eventId\": "
                                    + "\"3fa85f64-5717-4562-b3fc-2c963f66afa6\", \"code\": \"EARLY10\", "
                                    + "\"description\": \"10% off early tickets\", \"discountType\": \"PERCENT\", "
                                    + "\"discountValue\": 10.00, \"maxUses\": 100, \"maxUsesPerCustomer\": 1, "
                                    + "\"startsAt\": null, \"endsAt\": \"2026-09-08T23:59:59+02:00\", "
                                    + "\"categoryIds\": [], \"active\": true, \"uses\": 0, \"discountGiven\": 0.00, "
                                    + "\"createdAt\": \"2026-08-25T08:00:00+02:00\" } }"))),
            @ApiResponse(responseCode = "400", description = "A bad code or term",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Bad code", value = """
                                    { "code": "400 BAD_REQUEST", "message": "A promo code is 3 to 40 letters, digits, '-' or '_'.", "data": null }"""),
                            @ExampleObject(name = "Percentage too high", value = """
                                    { "code": "400 BAD_REQUEST", "message": "A percentage discount must be below 100: a code can't make tickets free.", "data": null }"""),
                            @ExampleObject(name = "End in the past", value = """
                                    { "code": "400 BAD_REQUEST", "message": "The end of the promo code must be in the future.", "data": null }"""),
                            @ExampleObject(name = "Unreadable end", value = """
                                    { "code": "400 BAD_REQUEST", "message": "The end of the promo code must be a date and time like 2026-09-08T23:59:59.", "data": null }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }"""))),
            @ApiResponse(responseCode = "409", description = "The event already has this code",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Exists",
                            value = """
                                    { "code": "409 CONFLICT", "message": "A promo code \\"EARLY10\\" already exists for this event.", "data": { "errorCode": "PROMO_CODE_EXISTS" } }""")))
    })
    public ResponseEntity<ApiResult<PromoCodeDTO>> create(@Valid @RequestBody CreatePromoCodeRequestDTO request,
                                                          Authentication authentication) {
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        PromoCodeDTO created = promoCodeService.create(request, AuthenticatedCaller.organizerUuid(authentication),
                actor(authentication), isAdmin);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Promo code created", created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
    @Operation(summary = "An event's promo codes",
            description = "Every code of the event, newest first, with `uses` (bookings using it that still "
                    + "count: not cancelled or expired) and `discountGiven` on them. The event's organizer, or "
                    + "platform staff.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The codes",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Codes",
                            value = "{ \"code\": \"200 OK\", \"message\": \"Promo codes retrieved successfully\", "
                                    + "\"data\": [ " + CODE_EXAMPLE + " ] }"))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }"""))),
            @ApiResponse(responseCode = "404", description = "Unknown event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found", "data": null }""")))
    })
    public ResponseEntity<ApiResult<List<PromoCodeDTO>>> list(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId, Authentication authentication) {
        List<PromoCodeDTO> codes = promoCodeService.list(eventId, AuthenticatedCaller.organizerUuid(authentication),
                AuthenticatedCaller.isPlatformStaff(authentication));
        return ResponseEntity.ok(ApiResult.ok("Promo codes retrieved successfully", codes));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Edit a promo code",
            description = "Replaces the code's terms (send every field you want to keep; `active: false` "
                    + "switches it off). The event and the code text never change. Bookings already made keep "
                    + "the discount they got.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Updated",
                            value = "{ \"code\": \"200 OK\", \"message\": \"Promo code updated\", \"data\": "
                                    + CODE_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "400", description = "A bad term",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Window",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "The start of the promo code must be before its end.", "data": null }"""))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }"""))),
            @ApiResponse(responseCode = "404", description = "Unknown code",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Not found",
                            value = """
                                    { "code": "404 NOT_FOUND", "message": "Promo code not found", "data": null }""")))
    })
    public ResponseEntity<ApiResult<PromoCodeDTO>> update(@Parameter(description = "Promo code UUID") @PathVariable UUID id,
                                                          @Valid @RequestBody PromoCodeTermsDTO request,
                                                          Authentication authentication) {
        boolean isAdmin = hasRole(authentication, "ROLE_SUPER_ADMIN");
        PromoCodeDTO updated = promoCodeService.update(id, request, AuthenticatedCaller.organizerUuid(authentication),
                actor(authentication), isAdmin);
        return ResponseEntity.ok(ApiResult.ok("Promo code updated", updated));
    }

    @PostMapping("/check")
    @SecurityRequirements
    @Operation(summary = "Check a promo code (public)",
            description = "Whether a code can be used on the event now, and what it gives, so the checkout can "
                    + "show it before booking. No sign-in. Not a reservation: POST /bookings checks it again "
                    + "and can still refuse it (used up meanwhile, or the buyer's own limit, "
                    + "PROMO_CODE_LIMIT_REACHED, which only the booking can check). Branch on data.errorCode.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usable",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Usable",
                            value = """
                                    { "code": "200 OK", "message": "Promo code is valid", "data": { "code": "EARLY10", "description": "10% off early tickets", "discountType": "PERCENT", "discountValue": 10.00, "categoryIds": [], "endsAt": "2026-09-08T23:59:59+02:00" } }"""))),
            @ApiResponse(responseCode = "400", description = "Unknown, switched off, or outside its window",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid", value = """
                                    { "code": "400 BAD_REQUEST", "message": "This promo code is not valid for this event.", "data": { "errorCode": "PROMO_CODE_INVALID" } }"""),
                            @ExampleObject(name = "Not started", value = """
                                    { "code": "400 BAD_REQUEST", "message": "This promo code can't be used yet.", "data": { "errorCode": "PROMO_CODE_NOT_STARTED" } }"""),
                            @ExampleObject(name = "Expired", value = """
                                    { "code": "400 BAD_REQUEST", "message": "This promo code has expired.", "data": { "errorCode": "PROMO_CODE_EXPIRED" } }""")
                    })),
            @ApiResponse(responseCode = "409", description = "Used up",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Used up",
                            value = """
                                    { "code": "409 CONFLICT", "message": "This promo code has been used up.", "data": { "errorCode": "PROMO_CODE_USED_UP" } }""")))
    })
    public ResponseEntity<ApiResult<PromoCodeCheckDTO>> check(@Valid @RequestBody PromoCodeCheckRequestDTO request) {
        return ResponseEntity.ok(ApiResult.ok("Promo code is valid",
                promoCodeService.check(request.getEventId(), request.getCode())));
    }

    private static String actor(Authentication authentication) {
        java.util.UUID organizer = AuthenticatedCaller.organizerUuid(authentication);
        return organizer != null ? organizer.toString() : authentication.getName();
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
