package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.service.TeamMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Onboarding + lifecycle endpoints for EVENT_ORGANIZER team members
 * (gate-staff / scanner operators). Mirrors {@link ShopStaffController}'s
 * shape — identity is owned in user-service, the relation is one-to-one
 * between the team member and the calling organizer, derived from the
 * caller's JWT (no organizerUuid in the request body, ever — that would
 * let one organizer create staff under another).
 *
 * <p>Disable semantics: soft-delete via DELETE — the row stays for audit
 * (see {@code booking_items.redeemed_by_user_uuid} + {@code redeemed_by_name}).
 * Outstanding JWTs are invalidated immediately by bumping the member's
 * {@code token_version}; refresh tokens are revoked in the same write so
 * the disabled member can't refresh into a fresh session.
 */
@RestController
@RequestMapping("/event-organizer/team-members")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Team Members",
     description = "EVENT_ORGANIZER team-member onboarding and lifecycle. Each team member is " +
                   "scoped to the calling organizer via the JWT's organizerUuid claim. Their JWT " +
                   "carries the SAME organizerUuid so booking-service can authorize ticket scans " +
                   "for any event owned by that organizer without a cross-service lookup.")
@SecurityRequirement(name = "bearerAuth")
public class TeamMemberController {

    private final TeamMemberService teamMemberService;

    @PostMapping
    // Deliberately organizer-only: create stamps the new TEAM_MEMBER with the
    // caller's organizerUuid claim, which SUPER_ADMIN tokens don't carry.
    // Admin-create would need an organizerUuid in the request body — a
    // separate contract change tracked as a follow-up.
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(
            summary = "Onboard a TEAM_MEMBER under your organizer account",
            description = "Creates a new TEAM_MEMBER user stamped with the calling organizer's " +
                          "user_uuid. The new user is created with a randomly-generated one-time " +
                          "temporary password, delivered to them over email/SMS — they must rotate " +
                          "it via POST /auth/change-password on first login. " +
                          "Requires **EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Team member created",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Team member created",
                                      "data": {
                                        "id": 91,
                                        "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                        "firstName": "Tariro",
                                        "middleName": "K",
                                        "lastName": "Chikomo",
                                        "email": "tariro@harare-arena.co.zw",
                                        "phoneNumber": "+263773456789",
                                        "roles": ["TEAM_MEMBER"],
                                        "defaultServices": ["ticketing"],
                                        "active": true,
                                        "createdAt": "2026-06-17T10:15:00",
                                        "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "Validation failed, or email/phone already registered",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Email already registered",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Only EVENT_ORGANIZER may manage team members",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> create(@Valid @RequestBody CreateTeamMemberDTO req) {
        UserResponseDTO data = teamMemberService.createTeamMember(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Team member created", data));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List every team member under your organizer account",
            description = "Returns every TEAM_MEMBER the calling organizer has created, active and " +
                          "disabled. Use the `active` field on each row to tell them apart. Requires " +
                          "**EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Team members retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team members retrieved",
                                      "data": [
                                        {
                                          "id": 91,
                                          "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                          "firstName": "Tariro",
                                          "middleName": "K",
                                          "lastName": "Chikomo",
                                          "email": "tariro@harare-arena.co.zw",
                                          "phoneNumber": "+263773456789",
                                          "roles": ["TEAM_MEMBER"],
                                          "defaultServices": ["ticketing"],
                                          "active": true,
                                          "createdAt": "2026-06-17T10:15:00",
                                          "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "Caller is not an EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Only EVENT_ORGANIZER may manage team members",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> list() {
        return ResponseEntity.ok(ApiResult.ok("Team members retrieved", teamMemberService.listMyTeam()));
    }

    @GetMapping("/{teamMemberUuid}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Retrieve one of your team members by uuid",
            description = "Returns the team member identified by `teamMemberUuid` if it belongs to " +
                          "the calling organizer; otherwise 404 (we never disclose existence of team " +
                          "members belonging to other organizers). Requires **EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Team member retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team member retrieved",
                                      "data": {
                                        "id": 91,
                                        "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                        "firstName": "Tariro",
                                        "middleName": "K",
                                        "lastName": "Chikomo",
                                        "email": "tariro@harare-arena.co.zw",
                                        "phoneNumber": "+263773456789",
                                        "roles": ["TEAM_MEMBER"],
                                        "defaultServices": ["ticketing"],
                                        "active": true,
                                        "createdAt": "2026-06-17T10:15:00",
                                        "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Team member not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> get(@PathVariable UUID teamMemberUuid) {
        return ResponseEntity.ok(ApiResult.ok("Team member retrieved",
                teamMemberService.getMyTeamMember(teamMemberUuid)));
    }

    @DeleteMapping("/{teamMemberUuid}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Disable a team member (soft-delete)",
            description = "Marks the team member inactive, bumps their token_version so every " +
                          "outstanding access token is rejected on next call, and revokes their " +
                          "refresh-token families so they can't refresh back in. The row stays for " +
                          "audit — any tickets they scanned still resolve their name via " +
                          "booking_items.redeemed_by_name. Re-enable later via " +
                          "PATCH /event-organizer/team-members/{teamMemberUuid}/enable. " +
                          "Idempotent: a second DELETE on an already-disabled member returns 200."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Team member disabled (or already disabled)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team member disabled",
                                      "data": {
                                        "id": 91,
                                        "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                        "firstName": "Tariro",
                                        "middleName": "K",
                                        "lastName": "Chikomo",
                                        "email": "tariro@harare-arena.co.zw",
                                        "phoneNumber": "+263773456789",
                                        "roles": ["TEAM_MEMBER"],
                                        "defaultServices": ["ticketing"],
                                        "active": false,
                                        "createdAt": "2026-06-17T10:15:00",
                                        "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Team member not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> disable(@PathVariable UUID teamMemberUuid) {
        return ResponseEntity.ok(ApiResult.ok("Team member disabled",
                teamMemberService.disableTeamMember(teamMemberUuid)));
    }

    @PatchMapping("/{teamMemberUuid}/enable")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Re-enable a previously disabled team member",
            description = "Flips `active` back to true. The member must log in again (the disable " +
                          "step revoked all their refresh tokens and bumped token_version) but their " +
                          "history, audit trail and existing role/scope are preserved. Idempotent: " +
                          "a second enable on an already-active member returns 200."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Team member enabled (or already active)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team member enabled",
                                      "data": {
                                        "id": 91,
                                        "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                        "firstName": "Tariro",
                                        "middleName": "K",
                                        "lastName": "Chikomo",
                                        "email": "tariro@harare-arena.co.zw",
                                        "phoneNumber": "+263773456789",
                                        "roles": ["TEAM_MEMBER"],
                                        "defaultServices": ["ticketing"],
                                        "active": true,
                                        "createdAt": "2026-06-17T10:15:00",
                                        "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Team member not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> enable(@PathVariable UUID teamMemberUuid) {
        return ResponseEntity.ok(ApiResult.ok("Team member enabled",
                teamMemberService.enableTeamMember(teamMemberUuid)));
    }

    @PostMapping("/{teamMemberUuid}/reset-password")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Re-issue a team member's temporary password",
            description = "Mints a fresh 10-character temporary password for the team member and " +
                          "re-delivers it via the same parallel(email + WhatsApp) → SMS-fallback " +
                          "channel pipeline used at onboarding. Use this when the original " +
                          "onboarding notification never reached them (spam-filtered, wrong " +
                          "number, etc.) and they can't log in. " +
                          "Sets `must_change_password=true` so the member is forced through the " +
                          "change-password flow on first login. The response NEVER contains the " +
                          "password itself — it travels only via the notification channels. " +
                          "Requires **EVENT_ORGANIZER** role; 404 if the member isn't yours."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Password reset and re-delivery dispatched",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Team member password reset",
                                      "data": {
                                        "id": 91,
                                        "userUuid": "5fc4c9d2-7a1b-4d3e-8c7f-1a2b3c4d5e6f",
                                        "firstName": "Tariro",
                                        "middleName": "K",
                                        "lastName": "Chikomo",
                                        "email": "tariro@harare-arena.co.zw",
                                        "phoneNumber": "+263773456789",
                                        "roles": ["TEAM_MEMBER"],
                                        "defaultServices": ["ticketing"],
                                        "active": true,
                                        "createdAt": "2026-06-17T10:15:00",
                                        "createdByOrganizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Team member not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> resetPassword(@PathVariable UUID teamMemberUuid) {
        return ResponseEntity.ok(ApiResult.ok("Team member password reset",
                teamMemberService.resetTemporaryPassword(teamMemberUuid)));
    }

    @GetMapping("/{teamMemberUuid}/events")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "List the events a team member is assigned to",
            description = "Returns the event IDs this team member may scan. **Deny-by-default**: " +
                          "an EMPTY list means the member can scan NOTHING — assign at least one " +
                          "event to make them useful at the gate. Adding/removing assignments takes " +
                          "effect on the next scan (no JWT re-issue required). " +
                          "Requires **EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Assigned event IDs (empty = no events visible / scannable)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Assigned events retrieved",
                                      "data": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Team member not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UUID>>> listAssignedEvents(@PathVariable UUID teamMemberUuid) {
        return ResponseEntity.ok(ApiResult.ok("Assigned events retrieved",
                teamMemberService.listAssignedEvents(teamMemberUuid)));
    }

    @PutMapping("/{teamMemberUuid}/events/{eventId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Assign a team member to an event",
            description = "Grants the team member access to this event. **Deny-by-default**: without " +
                          "at least one assignment a team member can see and scan NOTHING, so this " +
                          "is the call that makes them useful at the gate. Idempotent — assigning " +
                          "an already-assigned event is a no-op. Returns the member's full current " +
                          "assignment set so the UI can refresh in " +
                          "one call. NOTE: assigning an event you don't own is accepted but useless — " +
                          "the scan still fails the organizer-ownership check. Requires " +
                          "**EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Event assigned; returns the full assignment set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event assigned",
                                      "data": ["3fa85f64-5717-4562-b3fc-2c963f66afa6"]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Team member not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UUID>>> assignEvent(@PathVariable UUID teamMemberUuid,
                                                             @PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResult.ok("Event assigned",
                teamMemberService.assignEvent(teamMemberUuid, eventId)));
    }

    @DeleteMapping("/{teamMemberUuid}/events/{eventId}")
    @PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN')")
    @Operation(
            summary = "Remove a team member's event assignment",
            description = "Revokes the team member's access to this event. Idempotent. If this was " +
                          "their LAST assignment they lose all scan access (deny-by-default — they " +
                          "must be assigned at least one event to scan anything). Returns the " +
                          "member's full current assignment set. " +
                          "Requires **EVENT_ORGANIZER** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Event unassigned; returns the remaining assignment set",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Event unassigned",
                                      "data": []
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "No such team member under your organizer account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Team member not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<UUID>>> unassignEvent(@PathVariable UUID teamMemberUuid,
                                                               @PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResult.ok("Event unassigned",
                teamMemberService.unassignEvent(teamMemberUuid, eventId)));
    }
}
