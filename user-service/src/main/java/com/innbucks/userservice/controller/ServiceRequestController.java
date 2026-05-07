package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.CreateServiceRequestDTO;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users/me/service-requests")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Service Requests",
        description = "Authenticated users can request access to additional default services. SUPER_ADMIN approves them under /admin/service-requests.")
@SecurityRequirement(name = "bearerAuth")
public class ServiceRequestController {

    private final ServiceRequestService serviceRequestService;

    @GetMapping
    @Operation(
            summary = "List my services",
            description = "Returns every service bundle the authenticated user holds or has asked for, newest first. " +
                    "This combines two sources: explicit requests submitted via POST (PENDING or APPROVED) and " +
                    "any default services already on the user (e.g. picked at registration), which are surfaced " +
                    "as APPROVED rows. Each row is scoped to the caller — users can never see another user's data."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Caller's service requests retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Service requests retrieved",
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
                                        },
                                        {
                                          "id": 9,
                                          "userId": 42,
                                          "service": "ticketing",
                                          "status": "APPROVED",
                                          "createdAt": "2026-04-12T08:00:00",
                                          "reviewedAt": "2026-04-12T09:30:00",
                                          "reviewedBy": 1
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token")
    })
    public ResponseEntity<ApiResult<List<ServiceRequestResponseDTO>>> listMine(Authentication authentication) {
        List<ServiceRequestResponseDTO> body = serviceRequestService.listMine(authentication.getName());
        log.info("Service requests retrieved for {} count={}", authentication.getName(), body.size());
        return ResponseEntity.ok(ApiResult.ok("Service requests retrieved", body));
    }

    @PostMapping
    @Operation(
            summary = "Request access to an additional default service",
            description = "Submits a request to be granted access to a service bundle (e.g. 'loyalty'). " +
                    "Includes a free-text reason shown to the SUPER_ADMIN reviewing the request. " +
                    "The bundle must not already be granted to the user, and there must not already be a pending request for the same bundle."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Request submitted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Service request submitted",
                                      "data": {
                                        "id": 12,
                                        "userId": 42,
                                        "userEmail": "alice@innbucks.co.zw",
                                        "userFullName": "Alice Moyo",
                                        "service": "loyalty",
                                        "reason": "We are launching a rewards programme.",
                                        "status": "PENDING",
                                        "createdAt": "2026-05-07T10:30:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Unknown bundle, already-granted bundle, or duplicate pending request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token")
    })
    public ResponseEntity<ApiResult<ServiceRequestResponseDTO>> submit(
            Authentication authentication,
            @Valid @RequestBody CreateServiceRequestDTO request) {

        ServiceRequestResponseDTO body = serviceRequestService.submit(authentication.getName(), request);
        log.info("Service request submitted by {} for service={}", authentication.getName(), request.getService());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Service request submitted", body));
    }
}
