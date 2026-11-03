package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AssignSoldResultDTO;
import com.innbucks.bookingservice.dto.NumberingDTO;
import com.innbucks.bookingservice.dto.NumberingRequestDTO;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.TicketNumberingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Assigned ticket numbers (V34): a seat category's number series ("Bib T001,
 * T002, …"), set by the event's organizer. A ticket gets its number when it is
 * PAID for. Rides the gateway's {@code /bookings/**} route.
 */
@RestController
@RequestMapping("/bookings/numbering")
@RequiredArgsConstructor
@Tag(name = "Assigned ticket numbers", description = "Bib and seat numbers given to paid tickets, per category")
public class NumberingController {

    static final String NUMBERING_EXAMPLE = """
            {
              "categoryId": "5c2e7b1a-3d4f-4e6a-8b9c-0d1e2f3a4b5c",
              "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "label": "Bib",
              "prefix": "T",
              "start": 1,
              "padding": 3,
              "example": "T001",
              "assigned": 37,
              "nextNumber": "T038",
              "locked": true
            }""";
    static final String EXAMPLE_IN_USE = """
            { "code": "409 CONFLICT", "message": "Numbers have already been given in this category, so its numbering can't change.", "data": { "errorCode": "NUMBERING_IN_USE", "assigned": 37 } }""";
    static final String EXAMPLE_NO_NUMBERING = """
            { "code": "404 NOT_FOUND", "message": "This ticket category has no numbering.", "data": null }""";
    private static final String FORBIDDEN = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }""";
    private static final String CATEGORY_GONE = """
            { "code": "404 NOT_FOUND", "message": "This ticket category is no longer available.", "data": null }""";
    private static final String SEAT_SERVICE_DOWN = """
            { "code": "503 SERVICE_UNAVAILABLE", "message": "We're having trouble checking seat availability right now. Please try again in a moment.", "data": null }""";

    private final TicketNumberingService numbering;

    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Set a category's numbering",
            description = "Sets the category's number series, or replaces it while no number has been given. "
                    + "Paid tickets get the next number in the order the buyer listed them; a refunded ticket "
                    + "keeps its number and nobody else gets it. Once a number is out the series is locked "
                    + "(409 NUMBERING_IN_USE). Every field is optional: label \"Number\", no prefix, start 1, "
                    + "no padding.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Set",
                            value = """
                                    { "code": "200 OK", "message": "Numbering set", "data": { "categoryId": "5c2e7b1a-3d4f-4e6a-8b9c-0d1e2f3a4b5c", "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "label": "Bib", "prefix": "T", "start": 1, "padding": 3, "example": "T001", "assigned": 0, "nextNumber": "T001", "locked": false } }"""))),
            @ApiResponse(responseCode = "400", description = "A field breaks a rule",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Prefix",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "prefix": "Letters, digits and hyphens only, at most 10." } }"""))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer, or the event could not be checked",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "The category no longer exists",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Gone",
                            value = CATEGORY_GONE))),
            @ApiResponse(responseCode = "409", description = "A number has already been given in this category",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "In use",
                            value = EXAMPLE_IN_USE))),
            @ApiResponse(responseCode = "503", description = "The category could not be checked just now",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Retry",
                            value = SEAT_SERVICE_DOWN)))
    })
    public ResponseEntity<ApiResult<NumberingDTO>> set(
            @Parameter(description = "Seat category UUID") @PathVariable UUID categoryId,
            @Valid @RequestBody NumberingRequestDTO request, Authentication authentication) {
        NumberingDTO saved = numbering.set(categoryId, request, AuthenticatedCaller.organizerUuid(authentication),
                actor(authentication), isSuperAdmin(authentication));
        return ResponseEntity.ok(ApiResult.ok("Numbering set", saved));
    }

    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Remove a category's numbering",
            description = "Only while no number has been given (409 NUMBERING_IN_USE after).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Removed",
                            value = """
                                    { "code": "200 OK", "message": "Numbering removed", "data": null }"""))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "The category has no numbering",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "None",
                            value = EXAMPLE_NO_NUMBERING))),
            @ApiResponse(responseCode = "409", description = "A number has already been given",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "In use",
                            value = EXAMPLE_IN_USE)))
    })
    public ResponseEntity<ApiResult<Void>> remove(
            @Parameter(description = "Seat category UUID") @PathVariable UUID categoryId,
            Authentication authentication) {
        numbering.remove(categoryId, AuthenticatedCaller.organizerUuid(authentication), actor(authentication),
                isSuperAdmin(authentication));
        return ResponseEntity.ok(ApiResult.ok("Numbering removed", null));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
    @Operation(summary = "An event's numbering",
            description = "Every category of the event that numbers its tickets; a category without numbering "
                    + "is absent. The event's organizer; platform staff for any event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Listed",
                            value = "{ \"code\": \"200 OK\", \"message\": \"Numbering retrieved\", \"data\": [ "
                                    + NUMBERING_EXAMPLE + " ] }"))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<List<NumberingDTO>>> list(
            @Parameter(description = "Event UUID") @RequestParam UUID eventId, Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Numbering retrieved", numbering.list(eventId,
                AuthenticatedCaller.organizerUuid(authentication), AuthenticatedCaller.isPlatformStaff(authentication))));
    }

    @PostMapping("/{categoryId}/assign-sold")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(summary = "Number the tickets already sold",
            description = "Gives a number to every paid ticket of the category that has none (numbering added "
                    + "after sales began), in the order the bookings were made, continuing the series. "
                    + "Refunded and unpaid tickets get none. Safe to call twice: the second call numbers 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Numbered",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Numbered",
                            value = "{ \"code\": \"200 OK\", \"message\": \"Numbers given to 37 tickets\", "
                                    + "\"data\": { \"numberedNow\": 37, \"numbering\": " + NUMBERING_EXAMPLE
                                    + " } }"))),
            @ApiResponse(responseCode = "403", description = "Not the event's organizer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "The category has no numbering",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "None",
                            value = EXAMPLE_NO_NUMBERING)))
    })
    public ResponseEntity<ApiResult<AssignSoldResultDTO>> assignSold(
            @Parameter(description = "Seat category UUID") @PathVariable UUID categoryId,
            Authentication authentication) {
        AssignSoldResultDTO result = numbering.assignSold(categoryId,
                AuthenticatedCaller.organizerUuid(authentication), actor(authentication), isSuperAdmin(authentication));
        int n = result.numberedNow();
        return ResponseEntity.ok(ApiResult.ok("Numbers given to " + n + (n == 1 ? " ticket" : " tickets"), result));
    }

    private static String actor(Authentication authentication) {
        UUID organizer = AuthenticatedCaller.organizerUuid(authentication);
        return organizer != null ? organizer.toString() : authentication.getName();
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
