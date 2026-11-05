package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.exception.StaffPolicyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What is left of the organizer's team members: every path under
 * {@code /event-organizer/team-members} answers {@code 410 use_scanner_invite}.
 *
 * <p>Team members (a {@code TEAM_MEMBER} account with an email, a temporary
 * password and, as a system user, its own 2FA rules, for a shared-device
 * scanner app) were retired on 2026-11-05 (owner's decision, V55). Gate staff
 * are super-app scanners: the organizer adds a phone number and a name at
 * {@code /event-organizer/scanners} (event-service), and the person accepts and
 * scans in the InnBucks app. A scanner does everything a team member did (scan,
 * look up a booking, read their own scans) with no account here.
 *
 * <p>This answer exists so a portal build that still calls the old screen gets
 * a clear refusal and a pointer, rather than a 404. Any signed-in caller gets
 * it, for any method; nothing is read first.
 */
@RestController
@Tag(name = "Team Members (retired)",
     description = "Retired 2026-11-05. Every path answers 410 use_scanner_invite: add gate staff as "
             + "scanners with POST /event-organizer/scanners; they scan in the InnBucks app.")
@SecurityRequirement(name = "bearerAuth")
public class RetiredTeamMembersController {

    @RequestMapping(
            path = {"/event-organizer/team-members", "/event-organizer/team-members/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.PATCH, RequestMethod.DELETE})
    @Operation(
            deprecated = true,
            summary = "Retired: team members are gone, gate staff are scanners",
            description = "Every call under `/event-organizer/team-members` answers **410** "
                    + "`use_scanner_invite`, whatever its method or body. Add the person as a scanner "
                    + "with `POST /event-organizer/scanners` (a phone number and a name): they accept "
                    + "in the InnBucks app and scan there. Signed-out callers get the usual 401.")
    @ApiResponses({
            @ApiResponse(responseCode = "410", description = "Team members were retired",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "410 GONE",
                                      "message": "Team members can no longer be created. Add the person as a scanner with their phone number: they accept in the InnBucks app and scan from there.",
                                      "data": {
                                        "errorCode": "use_scanner_invite",
                                        "scannerPath": "/event-organizer/scanners"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "No valid session",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """)))
    })
    public ApiResult<Map<String, Object>> retired() {
        throw StaffPolicyException.useScannerInvite();
    }
}
