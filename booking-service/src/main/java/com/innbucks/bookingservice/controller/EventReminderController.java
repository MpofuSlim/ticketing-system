package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventReminderDTO;
import com.innbucks.bookingservice.dto.EventReminderRequestDTO;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.PriceTierReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Remind me before the price goes up" (V28). A signed-in customer follows an
 * event; about a day before the price tier on sale in one of its categories
 * ends, they get one message (WhatsApp, SMS fallback) per tier. The phone is
 * always the session's, never a request field, so a customer can only sign
 * up their own number. Rides the gateway's {@code /bookings/**} route.
 */
@RestController
@RequestMapping("/bookings/event-reminders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Price reminders", description = "Follow an event to hear before its price goes up")
public class EventReminderController {

    private static final String NO_PHONE_EXAMPLE = """
            { "code": "400 BAD_REQUEST", "message": "Reminders go to the phone on your account, and this session has none. Sign in with your phone.", "data": { "errorCode": "NO_PHONE_CLAIM" } }""";

    private final PriceTierReminderService reminders;

    @PostMapping
    @Operation(summary = "Follow an event for price reminders",
            description = "About a day before the price tier on sale in one of the event's categories ends (e.g. "
                    + "\"Early bird\"), the customer gets one message on the phone of their session: WhatsApp, "
                    + "or SMS if WhatsApp fails. One message per tier, never repeated. Idempotent. A customer may "
                    + "follow up to 50 events at a time.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Following",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Following",
                            value = """
                                    { "code": "201 CREATED", "message": "You'll be reminded before the price goes up", "data": { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "subscribedAt": "2026-09-02T10:15:00+02:00" } }"""))),
            @ApiResponse(responseCode = "400", description = "The session carries no phone",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "No phone",
                            value = NO_PHONE_EXAMPLE))),
            @ApiResponse(responseCode = "401", description = "Not signed in"),
            @ApiResponse(responseCode = "403", description = "Not a customer session"),
            @ApiResponse(responseCode = "409", description = "Following too many events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Limit",
                            value = """
                                    { "code": "409 CONFLICT", "message": "You can get reminders for at most 50 events at a time.", "data": { "errorCode": "REMINDER_LIMIT_REACHED" } }""")))
    })
    public ResponseEntity<ApiResult<EventReminderDTO>> subscribe(@Valid @RequestBody EventReminderRequestDTO request,
                                                                 Authentication authentication) {
        EventReminderDTO following = reminders.subscribe(request.getEventId(), phoneOf(authentication));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("You'll be reminded before the price goes up", following));
    }

    @DeleteMapping("/{eventId}")
    @Operation(summary = "Stop price reminders for an event",
            description = "Idempotent: an event not followed is not an error.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stopped",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Stopped",
                            value = """
                                    { "code": "200 OK", "message": "Price reminders stopped", "data": null }"""))),
            @ApiResponse(responseCode = "400", description = "The session carries no phone",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "No phone",
                            value = NO_PHONE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<Void>> unsubscribe(@Parameter(description = "Event UUID") @PathVariable UUID eventId,
                                                       Authentication authentication) {
        reminders.unsubscribe(eventId, phoneOf(authentication));
        return ResponseEntity.ok(ApiResult.ok("Price reminders stopped", null));
    }

    @GetMapping
    @Operation(summary = "Events followed for price reminders", description = "Newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Followed events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Following",
                            value = """
                                    { "code": "200 OK", "message": "Price reminders retrieved successfully", "data": [ { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "subscribedAt": "2026-09-02T10:15:00+02:00" } ] }"""))),
            @ApiResponse(responseCode = "400", description = "The session carries no phone",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "No phone",
                            value = NO_PHONE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<List<EventReminderDTO>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Price reminders retrieved successfully",
                reminders.list(phoneOf(authentication))));
    }

    private static String phoneOf(Authentication authentication) {
        Object details = authentication == null ? null : authentication.getDetails();
        return details instanceof JwtAuthDetails d ? d.phoneNumber() : null;
    }
}
