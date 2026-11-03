package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.scanner.CreateScannerRequestDTO;
import com.innbucks.eventservice.dto.scanner.ScannerResponseDTO;
import com.innbucks.eventservice.exception.ForbiddenException;
import com.innbucks.eventservice.security.AuthenticatedCaller;
import com.innbucks.eventservice.service.ScannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The organizer's super-app scanners (Foundry portal). A scanner is a phone
 * number and a name on the organizer's team; no account is created and no
 * password is sent. The person accepts in the super app, then scans there.
 *
 * <p>The shared-device login ({@code /event-organizer/team-members}, an account
 * with a temporary password, for the scanner app on venue phones) is a
 * different thing in user-service and is unchanged.
 *
 * <p>Writes are the owning organizer's only; platform staff read every
 * organizer's scanners and change none.
 */
@RestController
@RequestMapping("/event-organizer/scanners")
@RequiredArgsConstructor
@Tag(name = "Scanners", description = "An organizer's super-app gate scanners: phone numbers on their team.")
@SecurityRequirement(name = "bearerAuth")
public class ScannerController {

    private static final String READERS =
            "hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')";

    static final String SCANNER_EXAMPLE = """
            {
              "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Tendai",
              "lastName": "Moyo",
              "phoneNumber": "+263771234567",
              "status": "INVITED",
              "events": [],
              "createdAt": "2026-10-30T14:05:12+02:00"
            }""";

    static final String ASSIGNED_EXAMPLE = """
            {
              "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Tendai",
              "lastName": "Moyo",
              "phoneNumber": "+263771234567",
              "status": "ACTIVE",
              "events": [
                {
                  "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0",
                  "title": "Peterhouse Sixth Form Ball",
                  "venue": "Peterhouse, Marondera",
                  "startDateTime": "2026-11-07T18:00:00+02:00",
                  "endDateTime": "2026-11-08T01:00:00+02:00"
                }
              ],
              "createdAt": "2026-10-30T14:05:12+02:00"
            }""";

    private static final String NOT_FOUND_EXAMPLE = """
            { "code": "404 NOT_FOUND", "message": "Scanner not found.", "data": { "errorCode": "scanner_not_found" } }""";

    private static final String FORBIDDEN_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)", "data": null }""";

    private final ScannerService scannerService;

    @PostMapping
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Add a super-app scanner",
            description = "A phone number and a name. Every new scanner is `INVITED`, whether or not the number "
                    + "has an InnBucks account: the response never says. No message is sent; the person opens "
                    + "InnBucks and accepts. Requires **EVENT_ORGANIZER**.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Scanner added",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"201 CREATED\", \"message\": \"Scanner added\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "400", description = "Not a valid mobile number, or a name missing",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That isn't a valid mobile number.", "data": { "errorCode": "invalid_phone", "field": "phoneNumber" } }"""),
                            @ExampleObject(name = "Missing name", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "firstName": "First name is required" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Already on the team, or the team is full",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Already on the team", value = """
                                    { "code": "409 CONFLICT", "message": "This number is already on your team.", "data": { "errorCode": "scanner_already_on_team", "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e" } }"""),
                            @ExampleObject(name = "Team full", value = """
                                    { "code": "409 CONFLICT", "message": "Your team already has 500 scanners. Remove one before adding another.", "data": { "errorCode": "scanner_limit_reached", "limit": 500 } }""")
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> add(@Valid @RequestBody CreateScannerRequestDTO request,
                                                             Authentication authentication) {
        ScannerResponseDTO created = scannerService.add(organizer(authentication), authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Scanner added", created));
    }

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "List super-app scanners",
            description = "The organizer's scanners, oldest first, each with its assigned events. Platform staff "
                    + "see every organizer's, or one organizer's with `organizerUuid`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scanners",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanners retrieved\", \"data\": [ "
                                    + ASSIGNED_EXAMPLE + " ] }"))),
            @ApiResponse(responseCode = "403", description = "Not an organizer or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<List<ScannerResponseDTO>>> list(
            @Parameter(description = "Platform staff only: one organizer's scanners")
            @RequestParam(required = false) UUID organizerUuid,
            Authentication authentication) {
        UUID scope = AuthenticatedCaller.isPlatformStaff(authentication) ? organizerUuid : organizer(authentication);
        return ResponseEntity.ok(ApiResult.ok("Scanners retrieved", scannerService.list(scope)));
    }

    @GetMapping("/{scannerId}")
    @PreAuthorize(READERS)
    @Operation(summary = "Get one super-app scanner")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner retrieved\", \"data\": "
                                    + ASSIGNED_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not one of this organizer's scanners",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> get(@PathVariable UUID scannerId,
                                                             Authentication authentication) {
        UUID scope = AuthenticatedCaller.isPlatformStaff(authentication) ? null : organizer(authentication);
        return ResponseEntity.ok(ApiResult.ok("Scanner retrieved", scannerService.get(scope, scannerId)));
    }

    @PutMapping("/{scannerId}/events/{eventId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Assign a scanner to one of your events",
            description = "Idempotent. Refused when the number is already on another event at an overlapping "
                    + "time, with any organizer: a scanner is never on two overlapping events. The refusal names "
                    + "neither the other event nor its organizer. Checked when the assignment is made only; "
                    + "moving your event later is never blocked. A scanner can be assigned while still `INVITED`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner assigned\", \"data\": "
                                    + ASSIGNED_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner, or not your event",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Scanner", value = NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "Event", value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found.", "data": { "errorCode": "event_not_found" } }""")
                    })),
            @ApiResponse(responseCode = "409", description = "Overlaps another assignment, or the event has ended",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Clash", value = """
                                    { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }"""),
                            @ExampleObject(name = "Ended", value = """
                                    { "code": "409 CONFLICT", "message": "That event has already ended.", "data": { "errorCode": "event_ended" } }""")
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> assign(@PathVariable UUID scannerId,
                                                                @PathVariable UUID eventId,
                                                                Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner assigned", scannerService.assign(
                organizer(authentication), authentication.getName(), scannerId, eventId)));
    }

    @DeleteMapping("/{scannerId}/events/{eventId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Unassign a scanner from an event", description = "Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unassigned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner unassigned\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> unassign(@PathVariable UUID scannerId,
                                                                  @PathVariable UUID eventId,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner unassigned", scannerService.unassign(
                organizer(authentication), authentication.getName(), scannerId, eventId)));
    }

    @DeleteMapping("/{scannerId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Switch a scanner off",
            description = "Their next scan is refused. The record and its assignments are kept. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Scanner disabled", "data": { "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e", "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b", "firstName": "Tendai", "lastName": "Moyo", "phoneNumber": "+263771234567", "status": "DISABLED", "events": [], "createdAt": "2026-10-30T14:05:12+02:00" } }"""))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> disable(@PathVariable UUID scannerId,
                                                                 Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner disabled", scannerService.disable(
                organizer(authentication), authentication.getName(), scannerId)));
    }

    @PatchMapping("/{scannerId}/enable")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Switch a scanner back on",
            description = "Assignments are kept. Refused when one of them now overlaps an event the number took "
                    + "on elsewhere while this scanner was off. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Switched on",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner enabled\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "An assignment now overlaps",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }""")))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> enable(@PathVariable UUID scannerId,
                                                                Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner enabled", scannerService.enable(
                organizer(authentication), authentication.getName(), scannerId)));
    }

    @PostMapping("/{scannerId}/reinvite")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Invite a scanner again",
            description = "Turns a `DECLINED` scanner (they declined, or left) back to `INVITED`. Refused like "
                    + "enable when an assignment now overlaps.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invited again",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner invited again\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Not declined, or an assignment now overlaps",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not declined", value = """
                                    { "code": "409 CONFLICT", "message": "Only a scanner who declined can be invited again.", "data": { "errorCode": "scanner_not_declined" } }"""),
                            @ExampleObject(name = "Clash", value = """
                                    { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }""")
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> reinvite(@PathVariable UUID scannerId,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner invited again", scannerService.reinvite(
                organizer(authentication), authentication.getName(), scannerId)));
    }

    /**
     * The organizer the caller acts for: their {@code organizerUuid} claim,
     * which on an organizer's token is their own user uuid. A team member's
     * token carries the claim too, which is why every write also requires the
     * EVENT_ORGANIZER role.
     */
    private static UUID organizer(Authentication authentication) {
        UUID organizer = AuthenticatedCaller.organizerUuid(authentication);
        if (organizer == null) {
            organizer = AuthenticatedCaller.userUuid(authentication);
        }
        if (organizer == null) {
            throw new ForbiddenException("Your session has no organizer. Please sign in again.");
        }
        return organizer;
    }
}
