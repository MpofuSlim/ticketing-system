package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's directory of organizations, for platform staff.
 *
 * <p>Since loyalty and the marketplace moved ownership from a person's email to
 * the ORGANIZATION, an operator acting on a business's behalf — onboarding its
 * loyalty merchant, creating a marketplace listing for it — has to name the
 * organization by id. {@code /organizations/**} cannot help: it is scoped to
 * the caller's own memberships by design. This is the one list of every
 * business, and it lives under {@code /admin} behind its own permission for
 * exactly that reason.
 */
@RestController
@RequestMapping("/admin/organizations")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin — Organizations", description = "Platform directory of every organization (platform staff).")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrganizationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrganizationService organizationService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ORGANIZATIONS_READ + "')")
    @Operation(summary = "List every organization",
            description = "Sorted by name. `q` matches anywhere in the name, ignoring case. `product` "
                    + "narrows to organizations holding that product ACTIVE (`ticketing`, `loyalty` or "
                    + "`marketplace`). Each row carries the organization's active products and its "
                    + "OWNERs' emails, so two businesses sharing a name can be told apart.\n\n"
                    + "The `organizationId` is what loyalty's `POST /loyalty/merchants` and the "
                    + "marketplace's on-behalf listing create take. Requires `organizations:read` "
                    + "(SUPER_ADMIN holds it).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organizations retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "200 OK",
                              "message": "Organizations retrieved",
                              "data": {
                                "content": [
                                  {
                                    "organizationId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                                    "name": "Chikwanha Traders",
                                    "status": "ACTIVE",
                                    "products": ["loyalty", "marketplace"],
                                    "ownerEmails": ["rudo@chikwanha-traders.co.zw"],
                                    "contactEmail": "rudo@chikwanha-traders.co.zw",
                                    "createdAt": "2026-09-23T12:15:00+02:00"
                                  }
                                ],
                                "totalElements": 1,
                                "totalPages": 1,
                                "number": 0,
                                "size": 20
                              }
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Unknown product",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Unknown product. Use one of: ticketing, loyalty, marketplace.",
                              "data": { "errorCode": "unknown_product" }
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller lacks organizations:read")
    })
    public ResponseEntity<ApiResult<OrganizationDTOs.DirectoryPage>> list(
            @Parameter(description = "Part of the organization's name, case-insensitive")
            @RequestParam(name = "q", required = false) @Size(max = 100) String q,
            @Parameter(description = "Only organizations holding this product: ticketing, loyalty or marketplace")
            @RequestParam(name = "product", required = false) String product,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResult.ok("Organizations retrieved",
                organizationService.directory(q, product, page, size)));
    }
}
