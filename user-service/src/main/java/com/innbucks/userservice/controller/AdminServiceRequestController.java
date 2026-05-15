package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.ServiceRequestResponseDTO;
import com.innbucks.userservice.service.ServiceRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
        description = "SUPER_ADMIN endpoints for reviewing and approving user requests for additional default services.")
@SecurityRequirement(name = "bearerAuth")
public class AdminServiceRequestController {

    private final ServiceRequestService serviceRequestService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
                                        "id": 12,
                                        "userId": 42,
                                        "service": "loyalty",
                                        "status": "APPROVED",
                                        "createdAt": "2026-05-07T10:30:00",
                                        "reviewedAt": "2026-05-08T09:15:00",
                                        "reviewedBy": 1
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Request is no longer pending"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Request not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<ServiceRequestResponseDTO>> approve(
            @PathVariable Long id,
            Authentication authentication) {

        ServiceRequestResponseDTO body = serviceRequestService.approve(id, authentication.getName());
        log.info("Service request approved id={} by={}", id, authentication.getName());
        return ResponseEntity.ok(ApiResult.ok("Service request approved", body));
    }
}
