package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CopiedLayoutDTO;
import com.innbucks.seatservice.dto.CopyLayoutRequestDTO;
import com.innbucks.seatservice.dto.PriceByNameRequestDTO;
import com.innbucks.seatservice.dto.PriceByNameResultDTO;
import com.innbucks.seatservice.service.SeatCategoryService;
import com.innbucks.seatservice.service.SeatLayoutCopyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * S2S only: event-service's series generator copies a series' template layout
 * onto each new date (TIC-70), and an organizer's price change reaches every
 * future date of a series by category name (TIC-71). Off the internet (gateway {@code seat-internal-deny}
 * covers {@code /seat-categories/internal/**}); the exact POST is permitAll in
 * SecurityConfig, so this token check is the authentication.
 */
@RestController
@RequestMapping("/seat-categories/internal")
@Slf4j
@Tag(name = "Seat categories (internal)", description = "Calls between services. X-Internal-Token.")
public class SeatLayoutCopyController {

    private final SeatLayoutCopyService copyService;
    private final SeatCategoryService categoryService;

    @Value("${innbucks.internal-api-token:}")
    private String internalApiToken;

    public SeatLayoutCopyController(SeatLayoutCopyService copyService, SeatCategoryService categoryService) {
        this.copyService = copyService;
        this.categoryService = categoryService;
    }

    @PostMapping("/copy")
    @Operation(summary = "Copy an event's seat layout onto another (internal)",
            description = "Copies the source event's live categories onto the target: sections as fresh seats, "
                    + "the price tiers that have not ended (times unchanged) and the add-ons. Does nothing when the "
                    + "target already has a live category, and then maps the categories by name, so a repeated "
                    + "call is safe. Requires X-Internal-Token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Copied, or already in place",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Copied", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Layout copied",
                                      "data": {
                                        "copied": true,
                                        "categories": [
                                          { "sourceCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "name": "VIP" }
                                        ]
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "Already in place", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Layout already in place",
                                      "data": {
                                        "copied": false,
                                        "categories": [
                                          { "sourceCategoryId": "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11",
                                            "categoryId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "name": "VIP" }
                                        ]
                                      }
                                    }
                                    """)})),
            @ApiResponse(responseCode = "400", description = "Missing or same event ids",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Same event",
                            value = """
                                    { "code": "400 BAD_REQUEST", "message": "The source and target events must be different.", "data": null }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unauthorized",
                            value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Unauthorized", "data": null }
                                    """))),
            @ApiResponse(responseCode = "409", description = "More seats than the target's capacity; nothing copied",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Too many seats",
                            value = """
                                    { "code": "409 CONFLICT", "message": "The seat layout has 80 seats, more than the event's capacity of 60.", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<CopiedLayoutDTO>> copy(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) CopyLayoutRequestDTO request) {
        if (!internalTokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        }
        if (request == null || request.getSourceEventId() == null || request.getTargetEventId() == null) {
            return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                    "sourceEventId and targetEventId are required."));
        }
        if (request.getMaxTotalSeats() != null && request.getMaxTotalSeats() < 0) {
            return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                    "maxTotalSeats cannot be negative."));
        }
        CopiedLayoutDTO result = copyService.copy(
                request.getSourceEventId(), request.getTargetEventId(), request.getMaxTotalSeats());
        return ResponseEntity.ok(ApiResult.ok(result.isCopied() ? "Layout copied" : "Layout already in place", result));
    }

    @PostMapping("/price-by-name")
    @Operation(summary = "Set a category's price on several events by its name (internal)",
            description = "For each event, sets the full price of its live category with this name (letter case and "
                    + "surrounding spaces ignored). event-service uses it to change a price on every future date of a "
                    + "series. Each event is its own write: one refused by the tier rule does not stop the others. "
                    + "The rules of PUT /seat-categories/{id}: above 0, at most 2 decimals, and above every price tier "
                    + "that can still apply. Each change is recorded in the category's price history with changedBy "
                    + "as the actor. Sold tickets keep their price. At most 500 events. Requires X-Internal-Token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What happened to each event",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Done",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Prices set",
                                      "data": {
                                        "results": [
                                          { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "categoryId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                                            "outcome": "UPDATED", "oldPrice": 120.00, "message": null },
                                          { "eventId": "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                            "categoryId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                                            "outcome": "REFUSED", "oldPrice": 120.00,
                                            "message": "The ticket price must be higher than the price tier \\"Early bird\\" (150.00). Lower or remove that tier first." },
                                          { "eventId": "9e8d7c6b-5a49-4382-a716-152433425160",
                                            "categoryId": null, "outcome": "NO_SUCH_CATEGORY", "oldPrice": null,
                                            "message": null }
                                        ]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "A field is missing or wrong; nothing changed",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Price", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Price must be greater than 0.", "data": null }
                                    """),
                            @ExampleObject(name = "Too many", value = """
                                    { "code": "400 BAD_REQUEST", "message": "At most 500 events per call.", "data": null }
                                    """)})),
            @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Unauthorized",
                            value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Unauthorized", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<PriceByNameResultDTO>> priceByName(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) PriceByNameRequestDTO request) {
        if (!internalTokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        }
        if (request == null) {
            return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                    "eventIds, categoryName and price are required."));
        }
        PriceByNameResultDTO result = categoryService.setPriceByName(request.getEventIds(),
                request.getCategoryName(), request.getPrice(), request.getChangedBy());
        return ResponseEntity.ok(ApiResult.ok("Prices set", result));
    }

    private boolean internalTokenMatches(String token) {
        if (token == null || internalApiToken == null || internalApiToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                internalApiToken.getBytes(StandardCharsets.UTF_8));
    }
}
