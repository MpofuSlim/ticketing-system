package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSignInService;
import com.innbucks.userservice.devicesecurity.PartnerKeyAuthorizer;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.LoginResultRequest;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.LoginResultResponse;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.TicketRedeemRequest;
import com.innbucks.userservice.devicesecurity.dto.BrokerDTOs.TicketRedeemResponse;
import com.innbucks.userservice.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The broker's own calls (contract §3 rule 3, §5.4, §16.3). Service to service:
 * the app cannot call these, so a fraudster cannot fake a successful login.
 * Edge-reachable on purpose (the broker is a Firebase function on the public
 * internet), guarded by the broker's {@code x-api-key}.
 */
@RestController
@RequestMapping("/device-security/broker")
@RequiredArgsConstructor
@Tag(name = "DTX - Broker",
        description = "Called by the InnBucks broker, never by the app: login results, single-use ticket redemption, "
                + "and the public key the broker verifies tickets with.")
@SecurityRequirements({})
public class DeviceBrokerController {

    private final DeviceSignInService service;
    private final PartnerKeyAuthorizer partnerKeys;
    private final RequestValidation validation;

    @PostMapping("/login-result")
    @Operation(summary = "Report how staging's user login went (§5.4)",
            description = """
                    Call right after staging answers `/auth/client-service/user/login` (and `/pin-issue`, with the
                    PIN_ISSUE ticket). `SUCCESS` on a PENDING_PIN phone makes it TRUSTED and starts the 24-hour
                    new-phone cooling period; `LOCKED` after a correct OTP blocks the phone (someone holds the SIM but
                    not the PIN). Idempotent per ticket: a repeat report changes nothing and says `recorded: false`.
                    Carries no PIN and no token. Header: `x-api-key` (broker).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Login result recorded", "data": { "ticketId": "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m", "outcome": "SUCCESS", "recorded": true, "deviceState": "TRUSTED" } }
                            """))),
            @ApiResponse(responseCode = "400", description = "Malformed request, naming the field",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "400 BAD_REQUEST", "message": "outcome must be SUCCESS, WRONG_PIN, LOCKED, PIN_NOT_SET or ERROR", "data": { "errorCode": "validation_failed", "field": "outcome", "fields": { "outcome": "outcome must be SUCCESS, WRONG_PIN, LOCKED, PIN_NOT_SET or ERROR" } } }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong broker x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """))),
            @ApiResponse(responseCode = "404", description = "No such ticket",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "No login ticket with that id.", "data": { "errorCode": "ticket_not_found", "ticketId": "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m" } }
                            """)))
    })
    public ResponseEntity<ApiResult<LoginResultResponse>> loginResult(@RequestBody(required = false) LoginResultRequest body,
                                                                      HttpServletRequest request) {
        service.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        LoginResultResponse result = service.recordLoginResult(body);
        return ResponseEntity.ok(ApiResult.ok(result.recorded() ? "Login result recorded"
                : "Login result was already recorded for this ticket", result));
    }

    @PostMapping("/tickets/redeem")
    @Operation(summary = "Check and spend a login ticket (§3 rule 3)",
            description = """
                    Call BEFORE forwarding a user login (or pin-issue, or number check) to staging. DTX checks the
                    ticket's signature and expiry, that its number equals the login's username, that its device equals
                    the request's x-device-id and that its purpose matches, then spends it — atomically, so a ticket
                    lifted from one phone can never authorise a second attempt. Forward only when `data.valid` is true.
                    (The broker may instead verify the signature itself against the JWKS below, but single use can only
                    be enforced here.) Header: `x-api-key` (broker).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "valid=true: forward; valid=false: refuse, and reason says why",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Valid", value = """
                                    { "code": "200 OK", "message": "Ticket accepted", "data": { "valid": true, "ticketId": "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m", "purpose": "SIGN_IN", "reason": null } }
                                    """),
                            @ExampleObject(name = "Already used", value = """
                                    { "code": "200 OK", "message": "Ticket refused", "data": { "valid": false, "ticketId": "tkt_3n8p1r5t7v9x2z4b6d8f0h2k4m", "purpose": "SIGN_IN", "reason": "ALREADY_USED" } }
                                    """)
                    })),
            @ApiResponse(responseCode = "401", description = "Missing or wrong broker x-api-key",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "401 UNAUTHORIZED", "message": "Invalid or missing API key.", "data": { "errorCode": "invalid_api_key" } }
                            """)))
    })
    public ResponseEntity<ApiResult<TicketRedeemResponse>> redeem(@RequestBody(required = false) TicketRedeemRequest body,
                                                                  HttpServletRequest request) {
        service.requireEnabled();
        partnerKeys.require(PartnerKeyAuthorizer.Partner.BROKER, request);
        validation.check(body);
        TicketRedeemResponse result = service.redeem(body);
        return ResponseEntity.ok(ApiResult.ok(result.valid() ? "Ticket accepted" : "Ticket refused", result));
    }

    @GetMapping("/jwks.json")
    @Operation(summary = "The login-ticket public key, as a JWKS (§16.3)",
            description = "Public — no key needed. RS256; select by `kid`. Cache it, refresh on an unknown kid.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JWKS (a bare JWKS document, not the envelope — JOSE libraries read it directly)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "keys": [ { "kty": "RSA", "use": "sig", "alg": "RS256", "kid": "dtx-ticket-1", "n": "0vx7agoebGcQSuu...", "e": "AQAB" } ] }
                            """)))
    })
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(service.jwks());
    }
}
