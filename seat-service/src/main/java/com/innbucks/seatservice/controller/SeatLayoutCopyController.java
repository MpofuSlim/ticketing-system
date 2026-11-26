package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CopiedLayoutDTO;
import com.innbucks.seatservice.dto.CopyLayoutRequestDTO;
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
 * onto each new date. Off the internet (gateway {@code seat-internal-deny}
 * covers {@code /seat-categories/internal/**}); the exact POST is permitAll in
 * SecurityConfig, so this token check is the authentication.
 */
@RestController
@RequestMapping("/seat-categories/internal")
@Slf4j
@Tag(name = "Seat categories (internal)", description = "Calls between services. X-Internal-Token.")
public class SeatLayoutCopyController {

    private final SeatLayoutCopyService copyService;

    @Value("${innbucks.internal-api-token:}")
    private String internalApiToken;

    public SeatLayoutCopyController(SeatLayoutCopyService copyService) {
        this.copyService = copyService;
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

    private boolean internalTokenMatches(String token) {
        if (token == null || internalApiToken == null || internalApiToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                internalApiToken.getBytes(StandardCharsets.UTF_8));
    }
}
