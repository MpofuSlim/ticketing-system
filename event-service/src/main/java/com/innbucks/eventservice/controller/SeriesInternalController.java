package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.series.SeriesDateIdsDTO;
import com.innbucks.eventservice.service.EventSeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * A series' dates, for booking-service's reports per series (TIC-72). The
 * internal trio: the token is checked here, {@code SecurityConfig} permits
 * {@code GET /events/internal/*}, and the gateway's {@code event-internal-deny}
 * keeps {@code /events/internal/**} off the public internet.
 */
@Slf4j
@RestController
@RequestMapping("/events/internal")
@Tag(name = "Event series", description = "Recurring events: one set-up, a date for every start in the booking "
        + "window. Set the template up with the ordinary event endpoints.")
public class SeriesInternalController {

    static final String MESSAGE = "Series dates resolved";

    private final EventSeriesService seriesService;
    private final String expectedInternalToken;

    public SeriesInternalController(EventSeriesService seriesService,
                                    @Value("${innbucks.internal-api-token}") String expectedInternalToken) {
        this.seriesService = seriesService;
        this.expectedInternalToken = expectedInternalToken;
    }

    @GetMapping("/series-dates")
    @SecurityRequirements()
    @Operation(summary = "A series' dates and owner (internal)",
            description = """
                    Internal endpoint: booking-service asks it before a report filtered to a series, read live.
                    Lists every date the series has made, deleted ones included, never the template, and the
                    organizer the series belongs to. Always a 200 for a valid id, an unknown series included
                    (`found: false`), so a caller's circuit-breaker fallback can only mean "event-service could
                    not be asked". Requires the `X-Internal-Token` shared secret; the gateway's
                    `event-internal-deny` route makes `/events/internal/**` unreachable from the public internet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The series' dates",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Found", value = """
                                    {"code":"200 OK","message":"Series dates resolved","data":{
                                      "seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","found":true,
                                      "tenantUserUuid":"8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e",
                                      "eventIds":["7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                                  "2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091"]}}"""),
                            @ExampleObject(name = "No such series", value = """
                                    {"code":"200 OK","message":"Series dates resolved","data":{
                                      "seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f","found":false,
                                      "tenantUserUuid":null,"eventIds":[]}}""")
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Missing or invalid X-Internal-Token", "data": null }
                            """)))
    })
    public ResponseEntity<ApiResult<SeriesDateIdsDTO>> seriesDates(
            @Parameter(description = "Series UUID") @RequestParam UUID seriesId,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        if (!authorized(internalToken)) {
            log.warn("Unauthorized GET /events/internal/series-dates — missing or wrong X-Internal-Token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(HttpStatus.UNAUTHORIZED, "Missing or invalid X-Internal-Token"));
        }
        return ResponseEntity.ok(ApiResult.ok(MESSAGE, seriesService.dateIds(seriesId)));
    }

    private boolean authorized(String presented) {
        if (expectedInternalToken == null || expectedInternalToken.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedInternalToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
