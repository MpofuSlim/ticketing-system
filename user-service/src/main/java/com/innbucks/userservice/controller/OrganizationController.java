package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthenticatedCaller;
import com.innbucks.userservice.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Organizations: the business a person works for (V39).
 *
 * <p>Every endpoint acts for the CALLER, identified by the token's
 * {@code userUuid}, and scopes by membership: an organization the caller does
 * not belong to answers exactly like one that does not exist, so nothing here
 * reveals which businesses are on the platform.
 */
@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "The businesses the signed-in user belongs to, and their members.")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final UserRepository userRepository;

    @GetMapping("/me")
    @Operation(summary = "List my organizations",
            description = "Every organization the caller belongs to, with their role in each — the rows of "
                    + "the organization picker. Choose one with POST /auth/organization-context.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Organizations retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Organizations retrieved",
                              "data": [
                                {
                                  "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                                  "name": "Chikwanha Traders",
                                  "role": "OWNER",
                                  "status": "ACTIVE",
                                  "products": ["marketplace"]
                                }
                              ]
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "The token carries no userUuid",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Your session is missing your user identity. Please sign out and log in again.",
                              "data": null
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<List<OrganizationDTOs.OrganizationSummary>>> mine(Authentication authentication) {
        return ResponseEntity.ok(ApiResult.ok("Organizations retrieved",
                organizationService.listMine(requireCaller(authentication))));
    }

    @GetMapping("/{organizationId}")
    @Operation(summary = "Get an organization I belong to")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Organization retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Organization retrieved",
                              "data": {
                                "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                                "name": "Chikwanha Traders",
                                "contactEmail": "rudo@chikwanha-traders.co.zw",
                                "contactPhone": "+263772123456",
                                "address": "12 Samora Machel Ave, Harare",
                                "registrationNumber": "BR-2024-0417",
                                "status": "ACTIVE",
                                "products": ["marketplace"],
                                "yourRole": "OWNER"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a member, or no such organization — the same answer",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "404 NOT_FOUND",
                              "message": "We couldn't find that organization.",
                              "data": { "errorCode": "organization_not_found" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.OrganizationResponse>> get(
            Authentication authentication, @PathVariable UUID organizationId) {
        return ResponseEntity.ok(ApiResult.ok("Organization retrieved",
                organizationService.get(requireCaller(authentication), organizationId)));
    }

    @PutMapping("/{organizationId}")
    @Operation(summary = "Update my organization's details",
            description = "OWNER or ADMIN. Replaces the profile — send every field you want to keep.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Organization updated",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Organization updated",
                              "data": {
                                "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                                "name": "Chikwanha Traders",
                                "contactEmail": "rudo@chikwanha-traders.co.zw",
                                "contactPhone": "+263772123456",
                                "address": "12 Samora Machel Ave, Harare",
                                "registrationNumber": "BR-2024-0417",
                                "status": "ACTIVE",
                                "products": ["marketplace"],
                                "yourRole": "OWNER"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid body",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Validation failed",
                              "data": { "name": "name is required" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller is STAFF",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "403 FORBIDDEN",
                              "message": "Only an owner or admin can change the organization's details.",
                              "data": { "errorCode": "organization_role_insufficient" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a member, or no such organization",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "404 NOT_FOUND",
                              "message": "We couldn't find that organization.",
                              "data": { "errorCode": "organization_not_found" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.OrganizationResponse>> update(
            Authentication authentication, @PathVariable UUID organizationId,
            @Valid @RequestBody OrganizationDTOs.UpdateOrganizationRequest body) {
        return ResponseEntity.ok(ApiResult.ok("Organization updated",
                organizationService.update(requireCaller(authentication), organizationId, body)));
    }

    @GetMapping("/{organizationId}/members")
    @Operation(summary = "List the organization's members", description = "OWNER or ADMIN.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Members retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Members retrieved",
                              "data": [
                                {
                                  "userUuid": "3f6c1a2b-7d8e-4f90-a1b2-c3d4e5f60718",
                                  "firstName": "Rudo",
                                  "lastName": "Chikwanha",
                                  "email": "rudo@chikwanha-traders.co.zw",
                                  "role": "OWNER",
                                  "joinedAt": "2026-09-23T10:15:00"
                                },
                                {
                                  "userUuid": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d",
                                  "firstName": "Tendai",
                                  "lastName": "Moyo",
                                  "email": "tendai@chikwanha-traders.co.zw",
                                  "role": "STAFF",
                                  "joinedAt": "2026-09-23T11:02:00"
                                }
                              ]
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller is STAFF",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "403 FORBIDDEN",
                              "message": "Only an owner or admin can see the member list.",
                              "data": { "errorCode": "organization_role_insufficient" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Not a member, or no such organization",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "404 NOT_FOUND",
                              "message": "We couldn't find that organization.",
                              "data": { "errorCode": "organization_not_found" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<List<OrganizationDTOs.MemberResponse>>> members(
            Authentication authentication, @PathVariable UUID organizationId) {
        return ResponseEntity.ok(ApiResult.ok("Members retrieved",
                organizationService.listMembers(requireCaller(authentication), organizationId)));
    }

    @PostMapping("/{organizationId}/members")
    @Operation(summary = "Add an existing account to the organization",
            description = "OWNER may add any role; ADMIN may add STAFF. The person must already have an "
                    + "account. They see the organization at their next sign-in or token refresh, and will "
                    + "be asked to set up 2FA if they have not — belonging to a business requires it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Member added",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "201 CREATED",
                              "message": "Member added",
                              "data": {
                                "userUuid": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d",
                                "firstName": "Tendai",
                                "lastName": "Moyo",
                                "email": "tendai@chikwanha-traders.co.zw",
                                "role": "STAFF",
                                "joinedAt": "2026-09-23T11:02:00"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid body",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Validation failed",
                              "data": { "email": "must be a well-formed email address" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller may not add members, or may not add this role",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Staff adding", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Only an owner or admin can add members.",
                                      "data": { "errorCode": "organization_role_insufficient" }
                                    }
                                    """),
                            @ExampleObject(name = "Admin adding an admin", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Only an owner can add an owner or an admin.",
                                      "data": { "errorCode": "organization_role_insufficient" }
                                    }
                                    """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No account with that email, or the caller is not a member of the organization",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No such account", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "There's no account with that email. Ask them to register first, then add them.",
                                      "data": { "errorCode": "account_not_found" }
                                    }
                                    """),
                            @ExampleObject(name = "Not a member", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "We couldn't find that organization.",
                                      "data": { "errorCode": "organization_not_found" }
                                    }
                                    """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Already a member",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "409 CONFLICT",
                              "message": "That person is already a member of this organization.",
                              "data": { "errorCode": "already_member" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.MemberResponse>> addMember(
            Authentication authentication, @PathVariable UUID organizationId,
            @Valid @RequestBody OrganizationDTOs.AddMemberRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Member added",
                organizationService.addMember(requireCaller(authentication), organizationId, body)));
    }

    @PutMapping("/{organizationId}/members/{userUuid}")
    @Operation(summary = "Change a member's role",
            description = "OWNER only. The last OWNER cannot be demoted. The member's current tokens stop "
                    + "working immediately; their next refresh carries the new role.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Role changed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Role changed",
                              "data": {
                                "userUuid": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d",
                                "firstName": "Tendai",
                                "lastName": "Moyo",
                                "email": "tendai@chikwanha-traders.co.zw",
                                "role": "ADMIN",
                                "joinedAt": "2026-09-23T11:02:00"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller is not an OWNER",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "403 FORBIDDEN",
                              "message": "Only an owner can change roles.",
                              "data": { "errorCode": "organization_role_insufficient" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such member, or the caller is not a member of the organization",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "404 NOT_FOUND",
                              "message": "That person isn't a member of this organization.",
                              "data": { "errorCode": "member_not_found" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Would leave the organization with no owner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "409 CONFLICT",
                              "message": "An organization must keep at least one owner. Make someone else an owner first.",
                              "data": { "errorCode": "last_owner" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.MemberResponse>> changeRole(
            Authentication authentication, @PathVariable UUID organizationId, @PathVariable UUID userUuid,
            @Valid @RequestBody OrganizationDTOs.ChangeRoleRequest body) {
        return ResponseEntity.ok(ApiResult.ok("Role changed",
                organizationService.changeRole(requireCaller(authentication), organizationId, userUuid,
                        body.getRole())));
    }

    @DeleteMapping("/{organizationId}/members/{userUuid}")
    @Operation(summary = "Remove a member, or leave",
            description = "OWNER removes anyone; ADMIN removes STAFF; anyone may remove themselves. The last "
                    + "OWNER can do neither. The person's current tokens stop working immediately.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Member removed",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Member removed",
                              "data": null
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller may not remove this member",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Staff removing someone", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Only an owner or admin can remove members.",
                                      "data": { "errorCode": "organization_role_insufficient" }
                                    }
                                    """),
                            @ExampleObject(name = "Admin removing an admin", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "An admin can only remove staff.",
                                      "data": { "errorCode": "organization_role_insufficient" }
                                    }
                                    """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such member, or the caller is not a member of the organization",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "404 NOT_FOUND",
                              "message": "That person isn't a member of this organization.",
                              "data": { "errorCode": "member_not_found" }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Would leave the organization with no owner",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "409 CONFLICT",
                              "message": "An organization must keep at least one owner. Make someone else an owner first.",
                              "data": { "errorCode": "last_owner" }
                            }
                            """)))
    })
    public ResponseEntity<ApiResult<Void>> removeMember(
            Authentication authentication, @PathVariable UUID organizationId, @PathVariable UUID userUuid) {
        organizationService.removeMember(requireCaller(authentication), organizationId, userUuid);
        return ResponseEntity.ok(ApiResult.ok("Member removed", null));
    }

    /**
     * The caller, from the token's {@code userUuid}. A token without one is a
     * 400 asking them to sign in again — never an empty answer, which would
     * read as "you belong to nothing" and hide a broken session.
     */
    private User requireCaller(Authentication authentication) {
        UUID me = AuthenticatedCaller.userUuid(authentication);
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your session is missing your user identity. Please sign out and log in again.");
        }
        return userRepository.findByUserUuid(me).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Your session is missing your user identity. Please sign out and log in again."));
    }
}
