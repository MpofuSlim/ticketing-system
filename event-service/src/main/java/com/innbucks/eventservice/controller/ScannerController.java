package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.scanner.CreateScannerRequestDTO;
import com.innbucks.eventservice.dto.scanner.PortalAccessRequestDTO;
import com.innbucks.eventservice.dto.scanner.SalesEventTermsRequestDTO;
import com.innbucks.eventservice.dto.scanner.ScannerResponseDTO;
import com.innbucks.eventservice.dto.scanner.ScannerRolesRequestDTO;
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
 * The organizer's event team (Foundry portal). A team member is a phone
 * number and a name; no account is created and no password is sent. The
 * person accepts in the super app, then scans there, sells tickets (a sales
 * agent, V21), or both. A sales agent can also be sent a Foundry portal
 * invite, which user-service owns.
 *
 * <p>Gate staff are super-app scanners only. The scanner-app team members
 * (user-service accounts with a temporary password, for shared venue phones)
 * were retired on 2026-11-05; nothing in event-service reads them any more.
 *
 * <p>Platform staff read every organizer's team. Writes are the owning
 * organizer's, with one exception (owner's decision, 2026-11-13): InnBucks
 * staff (SUPER_ADMIN, PRODUCT_MANAGER; never the read-only PRODUCT_OFFICER)
 * manage an organizer's GATE SCANNERS: add one (naming the organizer), assign
 * it to the organizer's events, unassign, switch off and on, invite again.
 * A member who sells, roles, sales terms and portal access stay the
 * organizer's alone ({@code ScannerService.TeamEditor}). A scanner staff added
 * carries {@code addedByInnBucks}.
 */
@RestController
@RequestMapping("/event-organizer/scanners")
@RequiredArgsConstructor
@Tag(name = "Scanners", description = "An organizer's event team: phone numbers that scan tickets at the gate, "
        + "sell tickets (sales agents), or both.")
@SecurityRequirement(name = "bearerAuth")
public class ScannerController {

    private static final String READERS =
            "hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')";

    /**
     * Gate-scanner writes: the organizer, or InnBucks staff acting for one.
     * The "may act on anyone's" set (SUPER_ADMIN + PRODUCT_MANAGER), never the
     * read-only PRODUCT_OFFICER. Selling stays EVENT_ORGANIZER-only.
     */
    private static final String SCANNER_WRITERS = "hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_MANAGER')";

    private static final String STAFF_PARAM = "InnBucks staff only (SUPER_ADMIN, PRODUCT_MANAGER): the organizer "
            + "whose team this is. Optional here: the member's own organizer is used, and naming another is a 404. "
            + "An organizer leaves it out.";

    private static final String STAFF_NOTE = "InnBucks staff (SUPER_ADMIN, PRODUCT_MANAGER) may do this for any "
            + "organizer's gate scanner, but not for a member who sells (403 `sales_agent_organizer_only`).";

    private static final String SALES_AGENT_ORGANIZER_ONLY_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "Only the organizer can manage a sales agent. InnBucks staff can manage gate scanners only.", "data": { "errorCode": "sales_agent_organizer_only" } }""";

    static final String SCANNER_EXAMPLE = """
            {
              "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Tendai",
              "lastName": "Moyo",
              "phoneNumber": "+263771234567",
              "status": "INVITED",
              "canScan": true,
              "canSell": false,
              "events": [],
              "salesEvents": [],
              "portalEmail": null,
              "portalInvitedAt": null,
              "createdAt": "2026-10-30T14:05:12+02:00",
              "addedByInnBucks": false
            }""";

    /** A gate scanner InnBucks staff added for the organizer. */
    static final String STAFF_ADDED_EXAMPLE = """
            {
              "scannerId": "7d2e3f4a-5b6c-4d7e-8f90-1a2b3c4d5e6f",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Rudo",
              "lastName": "Chikwanha",
              "phoneNumber": "+263772345678",
              "status": "INVITED",
              "canScan": true,
              "canSell": false,
              "events": [],
              "salesEvents": [],
              "portalEmail": null,
              "portalInvitedAt": null,
              "createdAt": "2026-11-13T09:20:41+02:00",
              "addedByInnBucks": true
            }""";

    static final String ASSIGNED_EXAMPLE = """
            {
              "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Tendai",
              "lastName": "Moyo",
              "phoneNumber": "+263771234567",
              "status": "ACTIVE",
              "canScan": true,
              "canSell": false,
              "events": [
                {
                  "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0",
                  "title": "Peterhouse Sixth Form Ball",
                  "venue": "Peterhouse, Marondera",
                  "startDateTime": "2026-11-07T18:00:00+02:00",
                  "endDateTime": "2026-11-08T01:00:00+02:00"
                }
              ],
              "salesEvents": [],
              "portalEmail": null,
              "portalInvitedAt": null,
              "createdAt": "2026-10-30T14:05:12+02:00",
              "addedByInnBucks": false
            }""";

    /** The same person, also a sales agent for Jazz on the Lawn: everything up to the portal fields. */
    private static final String AGENT_HEAD = """
            {
              "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e",
              "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b",
              "firstName": "Tendai",
              "lastName": "Moyo",
              "phoneNumber": "+263771234567",
              "status": "ACTIVE",
              "canScan": true,
              "canSell": true,
              "events": [
                {
                  "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0",
                  "title": "Peterhouse Sixth Form Ball",
                  "venue": "Peterhouse, Marondera",
                  "startDateTime": "2026-11-07T18:00:00+02:00",
                  "endDateTime": "2026-11-08T01:00:00+02:00"
                }
              ],
              "salesEvents": [
                {
                  "eventId": "0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                  "title": "Jazz on the Lawn",
                  "venue": "Harare Gardens",
                  "startDateTime": "2026-11-14T15:00:00+02:00",
                  "endDateTime": "2026-11-14T21:00:00+02:00",
                  "ticketCap": 200,
                  "commissionPercent": 5.00
                }
              ],
            """;

    /** A sales agent with no portal invite. */
    static final String AGENT_EXAMPLE = AGENT_HEAD + """
              "portalEmail": null,
              "portalInvitedAt": null,
              "createdAt": "2026-10-30T14:05:12+02:00",
              "addedByInnBucks": false
            }""";

    /** The same agent once the portal invite went. */
    static final String AGENT_WITH_PORTAL_EXAMPLE = AGENT_HEAD + """
              "portalEmail": "tendai.moyo@gmail.com",
              "portalInvitedAt": "2026-11-06T10:00:00+02:00",
              "createdAt": "2026-10-30T14:05:12+02:00",
              "addedByInnBucks": false
            }""";

    private static final String NOT_FOUND_EXAMPLE = """
            { "code": "404 NOT_FOUND", "message": "Scanner not found.", "data": { "errorCode": "scanner_not_found" } }""";

    private static final String FORBIDDEN_EXAMPLE = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - you do not have the required role (EVENT_ORGANIZER or SUPER_ADMIN)", "data": null }""";

    private final ScannerService scannerService;

    @PostMapping
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Add a team member (gate scanner and/or sales agent)",
            description = "A phone number, a name, and what they do: `canScan` (default true) scans tickets at the "
                    + "gate, `canSell` (default false) sells tickets for you as a sales agent; at least one must "
                    + "be true. Every new team member is `INVITED`, whether or not the number has an InnBucks "
                    + "account: the response never says. Nothing is sent yet: the person gets a WhatsApp (SMS "
                    + "if WhatsApp fails) at their first event assignment of either kind, naming you, that event "
                    + "and their roles. Requires **EVENT_ORGANIZER**, or **SUPER_ADMIN / PRODUCT_MANAGER** adding "
                    + "a gate scanner for an organizer: they must name it with `organizerUuid` (an organizer with "
                    + "at least one event), cannot add a seller (`canSell`), and the member is marked "
                    + "`addedByInnBucks`.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Added",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "By the organizer", value =
                                    "{ \"code\": \"201 CREATED\", \"message\": \"Scanner added\", \"data\": "
                                            + SCANNER_EXAMPLE + " }"),
                            @ExampleObject(name = "By InnBucks staff", value =
                                    "{ \"code\": \"201 CREATED\", \"message\": \"Scanner added\", \"data\": "
                                            + STAFF_ADDED_EXAMPLE + " }")
                    })),
            @ApiResponse(responseCode = "400", description = "Not a valid mobile number, a name missing, no role, "
                    + "or (staff) no organizer named",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Invalid phone", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That isn't a valid mobile number.", "data": { "errorCode": "invalid_phone", "field": "phoneNumber" } }"""),
                            @ExampleObject(name = "Missing name", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "firstName": "First name is required" } }"""),
                            @ExampleObject(name = "No role", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Choose at least one: scan tickets or sell tickets.", "data": { "errorCode": "no_role" } }"""),
                            @ExampleObject(name = "Staff: no organizer", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Choose the organizer whose team this is.", "data": { "errorCode": "organizer_required" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an organizer or SUPER_ADMIN / PRODUCT_MANAGER; "
                    + "staff adding a seller; an organizer naming another organizer",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff adding a seller", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE),
                            @ExampleObject(name = "Another organizer's team", value = """
                                    { "code": "403 FORBIDDEN", "message": "You can only change your own team.", "data": null }""")
                    })),
            @ApiResponse(responseCode = "404", description = "Staff named an organizer with no events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "404 NOT_FOUND", "message": "That organizer has no events. Check the organizer and try again.", "data": { "errorCode": "organizer_not_found" } }"""))),
            @ApiResponse(responseCode = "409", description = "Already on the team, or the team is full",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Already on the team", value = """
                                    { "code": "409 CONFLICT", "message": "This number is already on your team.", "data": { "errorCode": "scanner_already_on_team", "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e" } }"""),
                            @ExampleObject(name = "Team full", value = """
                                    { "code": "409 CONFLICT", "message": "Your team already has 500 members. Remove one before adding another.", "data": { "errorCode": "scanner_limit_reached", "limit": 500 } }""")
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> add(
            @Valid @RequestBody CreateScannerRequestDTO request,
            @Parameter(description = "InnBucks staff only, and required for them: the organizer whose team this "
                    + "is. An organizer leaves it out.")
            @RequestParam(required = false) UUID organizerUuid,
            Authentication authentication) {
        ScannerResponseDTO created = scannerService.add(editor(authentication, organizerUuid), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Scanner added", created));
    }

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "List the event team",
            description = "The organizer's team, oldest first, each with its roles, the events it scans "
                    + "(`events`) and the events it sells for with your terms (`salesEvents`). Platform staff "
                    + "see every organizer's, or one organizer's with `organizerUuid`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanners retrieved\", \"data\": [ "
                                    + AGENT_WITH_PORTAL_EXAMPLE + " ] }"))),
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
    @Operation(summary = "Get one team member")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner retrieved\", \"data\": "
                                    + AGENT_WITH_PORTAL_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not one of this organizer's scanners",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> get(@PathVariable UUID scannerId,
                                                             Authentication authentication) {
        UUID scope = AuthenticatedCaller.isPlatformStaff(authentication) ? null : organizer(authentication);
        return ResponseEntity.ok(ApiResult.ok("Scanner retrieved", scannerService.get(scope, scannerId)));
    }

    @PutMapping("/{scannerId}/events/{eventId}")
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Assign a scanner to one of your events",
            description = "Idempotent. Refused when the number is already on another event at an overlapping "
                    + "time, with any organizer: a scanner is never on two overlapping events. The refusal names "
                    + "neither the other event nor its organizer. Checked when the assignment is made only; "
                    + "moving your event later is never blocked. A scanner can be assigned while still `INVITED`. "
                    + "The first assignment of an `INVITED` scanner sends the person one WhatsApp (SMS if "
                    + "WhatsApp fails) naming you and this event and saying to open InnBucks and accept; later "
                    + "assignments send nothing. Whether it was delivered is never reported. Only a team member "
                    + "with the Scan role (`canScan`) can be assigned to scan. " + STAFF_NOTE)
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
            @ApiResponse(responseCode = "409", description = "Overlaps another assignment, the event has ended, "
                    + "or the person does not scan",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Clash", value = """
                                    { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }"""),
                            @ExampleObject(name = "Ended", value = """
                                    { "code": "409 CONFLICT", "message": "That event has already ended.", "data": { "errorCode": "event_ended" } }"""),
                            @ExampleObject(name = "Not a scanner", value = """
                                    { "code": "409 CONFLICT", "message": "This person doesn't scan tickets for you. Give them the Scan role first.", "data": { "errorCode": "not_a_scanner" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not the organizer or SUPER_ADMIN / PRODUCT_MANAGER, "
                    + "or staff acting on a member who sells",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff, member sells", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE)
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> assign(@PathVariable UUID scannerId,
                                                                @PathVariable UUID eventId,
                                                                @Parameter(description = STAFF_PARAM)
                                                                @RequestParam(required = false) UUID organizerUuid,
                                                                Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner assigned", scannerService.assign(
                editor(authentication, organizerUuid), scannerId, eventId)));
    }

    @DeleteMapping("/{scannerId}/events/{eventId}")
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Unassign a scanner from an event", description = "Idempotent. " + STAFF_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unassigned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner unassigned\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "Not the organizer or SUPER_ADMIN / PRODUCT_MANAGER, "
                    + "or staff acting on a member who sells",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff, member sells", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE)
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> unassign(@PathVariable UUID scannerId,
                                                                  @PathVariable UUID eventId,
                                                                  @Parameter(description = STAFF_PARAM)
                                                                  @RequestParam(required = false) UUID organizerUuid,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner unassigned", scannerService.unassign(
                editor(authentication, organizerUuid), scannerId, eventId)));
    }

    @DeleteMapping("/{scannerId}")
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Switch a scanner off",
            description = "Their next scan and their next sale (app or portal) are refused. The record and its "
                    + "assignments are kept, and so are their past sales. Idempotent. " + STAFF_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Switched off",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Scanner disabled", "data": { "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e", "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b", "firstName": "Tendai", "lastName": "Moyo", "phoneNumber": "+263771234567", "status": "DISABLED", "canScan": true, "canSell": false, "events": [], "salesEvents": [], "portalEmail": null, "portalInvitedAt": null, "createdAt": "2026-10-30T14:05:12+02:00", "addedByInnBucks": false } }"""))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "403", description = "Not the organizer or SUPER_ADMIN / PRODUCT_MANAGER, "
                    + "or staff acting on a member who sells",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff, member sells", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE)
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> disable(@PathVariable UUID scannerId,
                                                                 @Parameter(description = STAFF_PARAM)
                                                                 @RequestParam(required = false) UUID organizerUuid,
                                                                 Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner disabled", scannerService.disable(
                editor(authentication, organizerUuid), scannerId)));
    }

    @PatchMapping("/{scannerId}/enable")
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Switch a scanner back on",
            description = "Assignments are kept. Refused when one of them now overlaps an event the number took "
                    + "on elsewhere while this scanner was off. Idempotent. " + STAFF_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Switched on",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Scanner enabled\", \"data\": "
                                    + SCANNER_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "404", description = "Not your scanner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "An assignment now overlaps",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }"""))),
            @ApiResponse(responseCode = "403", description = "Not the organizer or SUPER_ADMIN / PRODUCT_MANAGER, "
                    + "or staff acting on a member who sells",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff, member sells", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE)
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> enable(@PathVariable UUID scannerId,
                                                                @Parameter(description = STAFF_PARAM)
                                                                @RequestParam(required = false) UUID organizerUuid,
                                                                Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner enabled", scannerService.enable(
                editor(authentication, organizerUuid), scannerId)));
    }

    @PostMapping("/{scannerId}/reinvite")
    @PreAuthorize(SCANNER_WRITERS)
    @Operation(summary = "Invite a scanner again",
            description = "Turns a `DECLINED` scanner (they declined, or left) back to `INVITED`. Refused like "
                    + "enable when an assignment now overlaps. Sends the invite message again, naming the "
                    + "scanner's soonest assigned event; with none assigned, it goes at the next assignment. "
                    + STAFF_NOTE)
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
                    })),
            @ApiResponse(responseCode = "403", description = "Not the organizer or SUPER_ADMIN / PRODUCT_MANAGER, "
                    + "or staff acting on a member who sells",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Role", value = FORBIDDEN_EXAMPLE),
                            @ExampleObject(name = "Staff, member sells", value = SALES_AGENT_ORGANIZER_ONLY_EXAMPLE)
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> reinvite(@PathVariable UUID scannerId,
                                                                  @Parameter(description = STAFF_PARAM)
                                                                  @RequestParam(required = false) UUID organizerUuid,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Scanner invited again", scannerService.reinvite(
                editor(authentication, organizerUuid), scannerId)));
    }

    @PutMapping("/{scannerId}/roles")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Set what a team member does: scan, sell, or both",
            description = "Both fields are required and at least one must be true. Taking a role away keeps that "
                    + "role's assignments (they grant nothing until the role is back) and stops the person's next "
                    + "scan or sale at once; past sales stay under their name. Giving the Scan role back is "
                    + "refused like switching back on when one of their scanning events now overlaps an event "
                    + "the number scans for anyone. Sends nothing. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles set",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Roles updated\", \"data\": "
                                    + AGENT_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "400", description = "A field missing, or both false",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No role", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Choose at least one: scan tickets or sell tickets.", "data": { "errorCode": "no_role" } }"""),
                            @ExampleObject(name = "Missing field", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "canSell": "canSell is required" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not your team member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Scan given back, and a scanning event now overlaps",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "409 CONFLICT", "message": "This number is already assigned to another event at that time.", "data": { "errorCode": "scanner_schedule_clash" } }""")))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> setRoles(@PathVariable UUID scannerId,
                                                                  @Valid @RequestBody ScannerRolesRequestDTO request,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Roles updated", scannerService.setRoles(
                organizer(authentication), authentication.getName(), scannerId,
                request.canScan(), request.canSell())));
    }

    @PutMapping("/{scannerId}/sales-events/{eventId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Let a sales agent sell for one of your events, on your terms",
            description = "Creates the assignment or replaces its terms. `ticketCap` (optional, at least 1) is the "
                    + "most tickets the agent may have sold for the event; voided and expired sales free theirs. "
                    + "`commissionPercent` (optional, default 0, 0 to 100, at most 2 decimals) is on what the "
                    + "buyer paid for each confirmed sale; you pay it to the agent yourself. No body = no cap and "
                    + "0%. Requires the Sell role (`canSell`). No overlap rule: an agent may sell for overlapping "
                    + "events. The first assignment of either kind of an `INVITED` person sends them one WhatsApp "
                    + "(SMS if WhatsApp fails) naming you, this event and their roles. A changed cap or commission "
                    + "applies from the next sale; nothing already sold is restated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned, or terms replaced",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Sales event assigned\", \"data\": "
                                    + AGENT_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "400", description = "A cap below 1, or a commission out of range or with "
                    + "more than 2 decimals",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Cap", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "ticketCap": "Ticket cap must be at least 1" } }"""),
                            @ExampleObject(name = "Commission", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "commissionPercent": "Commission can have at most 2 decimals" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not your team member, or not your event",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Team member", value = NOT_FOUND_EXAMPLE),
                            @ExampleObject(name = "Event", value = """
                                    { "code": "404 NOT_FOUND", "message": "Event not found.", "data": { "errorCode": "event_not_found" } }""")
                    })),
            @ApiResponse(responseCode = "409", description = "Not a sales agent, or the event has ended",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not a sales agent", value = """
                                    { "code": "409 CONFLICT", "message": "This person doesn't sell tickets for you. Give them the Sell role first.", "data": { "errorCode": "not_a_sales_agent" } }"""),
                            @ExampleObject(name = "Ended", value = """
                                    { "code": "409 CONFLICT", "message": "That event has already ended.", "data": { "errorCode": "event_ended" } }""")
                    }))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> assignSalesEvent(
            @PathVariable UUID scannerId,
            @PathVariable UUID eventId,
            @Valid @RequestBody(required = false) SalesEventTermsRequestDTO terms,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Sales event assigned", scannerService.assignSalesEvent(
                organizer(authentication), authentication.getName(), scannerId, eventId,
                terms == null ? null : terms.ticketCap(), terms == null ? null : terms.commissionPercent())));
    }

    @DeleteMapping("/{scannerId}/sales-events/{eventId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Stop a sales agent selling for an event",
            description = "Their next sale for the event is refused, in the app and the portal. Past sales stay "
                    + "under their name. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unassigned",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "200 OK", "message": "Sales event unassigned", "data": { "scannerId": "4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e", "organizerUuid": "8f14e45f-ceea-467a-9b0e-1c2d3e4f5a6b", "firstName": "Tendai", "lastName": "Moyo", "phoneNumber": "+263771234567", "status": "ACTIVE", "canScan": true, "canSell": true, "events": [ { "eventId": "5772554b-9c1d-4e2f-a3b4-c5d6e7f8a9b0", "title": "Peterhouse Sixth Form Ball", "venue": "Peterhouse, Marondera", "startDateTime": "2026-11-07T18:00:00+02:00", "endDateTime": "2026-11-08T01:00:00+02:00" } ], "salesEvents": [], "portalEmail": null, "portalInvitedAt": null, "createdAt": "2026-10-30T14:05:12+02:00", "addedByInnBucks": false } }"""))),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not your team member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> unassignSalesEvent(@PathVariable UUID scannerId,
                                                                            @PathVariable UUID eventId,
                                                                            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Sales event unassigned", scannerService.unassignSalesEvent(
                organizer(authentication), authentication.getName(), scannerId, eventId)));
    }

    @PutMapping("/{scannerId}/portal-access")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Send a sales agent a Foundry portal invite",
            description = "So the agent can also sell from the Foundry portal. InnBucks emails the invite to "
                    + "`email`; the agent opens it, confirms a code sent to this team member's phone, and sets a "
                    + "password. The portal sign-in is the same InnBucks account as the app on that phone and "
                    + "asks for a second factor. Sending again replaces the earlier invite. Requires the Sell "
                    + "role, and a team member who is neither switched off nor declined. A refused address comes "
                    + "back with its own `errorCode` (e.g. `invalid_email`, or `email_domain_reserved`: an "
                    + "InnBucks address is never an agent's). Nothing is stored unless the invite went.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invite sent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Portal invite sent\", \"data\": "
                                    + AGENT_WITH_PORTAL_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "400", description = "The address was refused",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not an address", value = """
                                    { "code": "400 BAD_REQUEST", "message": "That isn't a valid email address.", "data": { "errorCode": "invalid_email", "field": "email" } }"""),
                            @ExampleObject(name = "InnBucks address", value = """
                                    { "code": "400 BAD_REQUEST", "message": "InnBucks staff addresses can't be used here. Your administrator will invite you.", "data": { "errorCode": "email_domain_reserved", "field": "email" } }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not your team member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE))),
            @ApiResponse(responseCode = "409", description = "Not a sales agent, switched off, or declined",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not a sales agent", value = """
                                    { "code": "409 CONFLICT", "message": "This person doesn't sell tickets for you. Give them the Sell role first.", "data": { "errorCode": "not_a_sales_agent" } }"""),
                            @ExampleObject(name = "Switched off", value = """
                                    { "code": "409 CONFLICT", "message": "This person is switched off. Switch them back on first.", "data": { "errorCode": "scanner_disabled" } }"""),
                            @ExampleObject(name = "Declined", value = """
                                    { "code": "409 CONFLICT", "message": "This person declined your invite. Invite them again first.", "data": { "errorCode": "scanner_declined" } }""")
                    })),
            @ApiResponse(responseCode = "503", description = "The invite could not be sent; nothing was stored. Retry.",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            { "code": "503 SERVICE_UNAVAILABLE", "message": "We couldn't send the portal invite just now. Please try again.", "data": { "errorCode": "portal_invite_unavailable" } }""")))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> grantPortalAccess(
            @PathVariable UUID scannerId,
            @RequestBody PortalAccessRequestDTO request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Portal invite sent", scannerService.grantPortalAccess(
                organizer(authentication), authentication.getName(), scannerId, request.email())));
    }

    @DeleteMapping("/{scannerId}/portal-access")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Withdraw a sales agent's portal invite",
            description = "Clears the invite here and revokes any invite not yet used. An agent who already "
                    + "signed in to the portal keeps the account; to stop their selling, take the Sell role "
                    + "away, unassign their events, or switch them off: every sale is checked live. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value =
                            "{ \"code\": \"200 OK\", \"message\": \"Portal access removed\", \"data\": "
                                    + AGENT_EXAMPLE + " }"))),
            @ApiResponse(responseCode = "403", description = "Not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN_EXAMPLE))),
            @ApiResponse(responseCode = "404", description = "Not your team member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<ScannerResponseDTO>> revokePortalAccess(@PathVariable UUID scannerId,
                                                                            Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Portal access removed", scannerService.revokePortalAccess(
                organizer(authentication), authentication.getName(), scannerId)));
    }

    /**
     * Who is changing the team. An EVENT_ORGANIZER changes their own, and naming
     * another organizer is refused unless they are also InnBucks staff. Anyone
     * else got past {@link #SCANNER_WRITERS}, so is SUPER_ADMIN or
     * PRODUCT_MANAGER, acting for the organizer named (or, on a member's own
     * actions, the member's organizer). The service holds the staff rules.
     */
    private static ScannerService.TeamEditor editor(Authentication authentication, UUID organizerUuid) {
        String actor = authentication.getName();
        boolean staff = hasRole(authentication, "ROLE_SUPER_ADMIN") || hasRole(authentication, "ROLE_PRODUCT_MANAGER");
        if (hasRole(authentication, "ROLE_EVENT_ORGANIZER")) {
            UUID own = organizer(authentication);
            if (organizerUuid == null || organizerUuid.equals(own)) {
                return ScannerService.TeamEditor.organizer(own, actor);
            }
            if (!staff) {
                throw new ForbiddenException("You can only change your own team.");
            }
        }
        return ScannerService.TeamEditor.innbucksStaff(organizerUuid, actor);
    }

    private static boolean hasRole(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /**
     * The organizer the caller acts for: their {@code organizerUuid} claim,
     * which on an organizer's token is their own user uuid. The claim alone
     * grants nothing: every write also requires the EVENT_ORGANIZER role.
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
