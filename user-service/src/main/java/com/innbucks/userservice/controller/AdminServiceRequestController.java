package com.innbucks.userservice.controller;

import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.RejectServiceRequestDTO;
import com.innbucks.userservice.dto.ServiceRequestResponseDTO;
import com.innbucks.userservice.service.ServiceRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/service-requests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - Service Requests",
        description = "SUPER_ADMIN endpoints for reviewing, approving and rejecting user requests for additional default services.")
@SecurityRequirement(name = "bearerAuth")
public class AdminServiceRequestController {

    private final ServiceRequestService serviceRequestService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SERVICE_REQUESTS_READ + "')")
    @Operation(
            summary = "List pending service requests",
            description = "Returns every service request with status=PENDING, oldest first. Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Pending requests retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Pending service requests retrieved",
                                      "data": [
                                        {
                                          "id": 14,
                                          "userId": 42,
                                          "userEmail": "alice@innbucks.co.zw",
                                          "userFullName": "Alice Moyo",
                                          "service": "marketplace",
                                          "reason": "We want to sell our products on the InnBucks marketplace.",
                                          "status": "PENDING",
                                          "createdAt": "2026-08-05T18:45:00"
                                        },
                                        {
                                          "id": 12,
                                          "userId": 42,
                                          "userEmail": "alice@innbucks.co.zw",
                                          "userFullName": "Alice Moyo",
                                          "service": "loyalty",
                                          "reason": "We are launching a rewards programme.",
                                          "status": "PENDING",
                                          "createdAt": "2026-05-07T10:30:00"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<ServiceRequestResponseDTO>>> listPending() {
        List<ServiceRequestResponseDTO> body = serviceRequestService.listPending();
        log.info("Pending service requests retrieved count={}", body.size());
        return ResponseEntity.ok(ApiResult.ok("Pending service requests retrieved", body));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SERVICE_REQUESTS_APPROVE + "')")
    @Operation(
            summary = "Approve a pending service request",
            description = "Adds the requested bundle to the user's defaultServices and grants the matching role. " +
                    "The user must log in again to receive a JWT carrying the new service/role claims. " +
                    "Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Request approved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Service request approved",
                                      "data": {
                                        "id": 14,
                                        "userId": 42,
                                        "service": "marketplace",
                                        "status": "APPROVED",
                                        "createdAt": "2026-08-05T18:45:00",
                                        "reviewedAt": "2026-08-05T19:15:00",
                                        "reviewedBy": 1,
                                        "decisionReason": null
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Request is no longer pending",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "Service request 14 is not pending (status=REJECTED).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Request not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Service request not found: 99", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<ServiceRequestResponseDTO>> approve(
            @PathVariable Long id,
            Authentication authentication) {

        ServiceRequestResponseDTO body = serviceRequestService.approve(id, authentication.getName());
        log.info("Service request approved id={} by={}", id, authentication.getName());
        return ResponseEntity.ok(ApiResult.ok("Service request approved", body));
    }

    @PutMapping("/{id}/reject")
    // Deliberately the SAME permission as approve: both are the power to DECIDE
    // a request. A role that could approve but not reject could only ever say
    // yes, which recreates the undrainable queue this endpoint exists to fix.
    @PreAuthorize("hasAuthority('" + PermissionCatalog.SERVICE_REQUESTS_APPROVE + "')")
    @Operation(
            summary = "Reject a pending service request",
            description = "Records the decision with the reviewer's reason and notifies the requester. " +
                    "Grants nothing and leaves the user's roles and bundles untouched. " +
                    "The `reason` is REQUIRED and is shown to the requester verbatim. " +
                    "A rejection decides one request, not the bundle forever — the user may submit a " +
                    "new request for the same bundle afterwards. Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Request rejected",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Service request rejected",
                                      "data": {
                                        "id": 14,
                                        "userId": 42,
                                        "userEmail": "alice@rudo.co.zw",
                                        "userFullName": "Alice Moyo",
                                        "service": "marketplace",
                                        "reason": "We want to sell our products on the InnBucks marketplace.",
                                        "status": "REJECTED",
                                        "createdAt": "2026-08-05T18:45:00",
                                        "reviewedAt": "2026-08-05T19:15:00",
                                        "reviewedBy": 1,
                                        "decisionReason": "Your business verification is still outstanding - please complete it and re-apply."
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Reason missing/blank, or the request is no longer pending",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No reason",
                                    description = "Bean-validation shape: the per-field messages are in 'data'.",
                                    value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "reason": "reason is required" }
                                    }
                                    """),
                            @ExampleObject(name = "Already decided", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Service request 14 is not pending (status=APPROVED).", "data": null }
                                    """)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Request not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Service request not found: 99", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<ServiceRequestResponseDTO>> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectServiceRequestDTO body,
            Authentication authentication) {

        ServiceRequestResponseDTO result =
                serviceRequestService.reject(id, authentication.getName(), body.getReason());
        log.info("Service request rejected id={} by={}", id, authentication.getName());
        return ResponseEntity.ok(ApiResult.ok("Service request rejected", result));
    }
}
