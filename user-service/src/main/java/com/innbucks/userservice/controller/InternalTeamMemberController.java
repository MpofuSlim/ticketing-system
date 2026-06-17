package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.ScanAccessDTO;
import com.innbucks.userservice.service.TeamMemberService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Service-to-service scan-authorization lookup consumed by booking-service's
 * ticket-scan flow: "may team member X scan tickets for event Y?". Gated by
 * the shared {@code X-Internal-Token} header (the caller is another backend,
 * not a logged-in user).
 *
 * <p>Class-level {@link Hidden} keeps this out of the public Swagger UI; the
 * gateway additionally blocks {@code /users/internal/**} at the edge via the
 * {@code user-internal-deny} route. Together with the token check here that's
 * the "three files agree" contract for an internal endpoint.
 */
@RestController
@RequestMapping("/users/internal/team-members")
@Slf4j
@Hidden
public class InternalTeamMemberController {

    private final TeamMemberService teamMemberService;
    private final String expectedToken;

    public InternalTeamMemberController(TeamMemberService teamMemberService,
                                        @Value("${innbucks.internal-api-token:}") String expectedToken) {
        this.teamMemberService = teamMemberService;
        this.expectedToken = expectedToken;
    }

    @GetMapping("/{teamMemberUuid}/can-scan/{eventId}")
    @Operation(summary = "(S2S) May this team member scan tickets for this event?")
    public ResponseEntity<?> canScan(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID teamMemberUuid,
            @PathVariable UUID eventId) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean allowed = teamMemberService.canScanEvent(teamMemberUuid, eventId);
        return ResponseEntity.ok(ApiResult.ok("Scan access resolved",
                ScanAccessDTO.builder().allowed(allowed).build()));
    }

    private boolean authorized(String presented) {
        if (expectedToken == null || expectedToken.isBlank()) {
            log.warn("Internal API token is not configured; rejecting call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
