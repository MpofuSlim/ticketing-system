package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.invoice.BillingConfigRequest;
import com.innbucks.bookingservice.dto.invoice.BillingConfigResponse;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import com.innbucks.bookingservice.service.BillingConfigService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin management of per-organizer billing terms (the commission rate + cycle
 * each organizer's invoices are generated at). SUPER_ADMIN only — organizers do
 * not set their own rates.
 *
 * <p>Mounted under {@code /invoices/billing-config} so it shares the single
 * {@code /invoices/**} gateway route; the literal path takes precedence over
 * {@code /invoices/{id}} in Spring's matching.
 */
@RestController
@RequestMapping("/invoices/billing-config")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Invoice Billing Config",
        description = "Per-organizer commission rate + billing cycle used when generating invoices. "
                + "Organizers without an entry use the deployment defaults.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class OrganizerBillingConfigController {

    private final BillingConfigService billingConfigService;

    @GetMapping
    @Operation(summary = "List billing-config overrides",
            description = "Every organizer with an explicit override. Organizers not listed here are billed at "
                    + "the deployment defaults.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Overrides listed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Billing configs retrieved",
                                      "data": [
                                        { "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                          "commissionRate": 12.5000, "billingCycle": "MONTHLY",
                                          "currency": "USD", "overridden": true }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<BillingConfigResponse>>> list() {
        List<BillingConfigResponse> data = billingConfigService.listOverrides().stream()
                .map(c -> toResponse(c, true))
                .toList();
        return ResponseEntity.ok(ApiResult.ok("Billing configs retrieved", data));
    }

    @GetMapping("/{organizerUuid}")
    @Operation(summary = "Get an organizer's effective billing terms",
            description = "Returns the persisted override if one exists, otherwise the deployment defaults with "
                    + "`overridden=false`.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Effective terms",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Billing config retrieved",
                                      "data": { "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                                "commissionRate": 10.0000, "billingCycle": "MONTHLY",
                                                "currency": "USD", "overridden": false }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<BillingConfigResponse>> get(@PathVariable UUID organizerUuid) {
        OrganizerBillingConfig config = billingConfigService.resolve(organizerUuid);
        boolean overridden = billingConfigService.hasOverride(organizerUuid);
        return ResponseEntity.ok(ApiResult.ok("Billing config retrieved", toResponse(config, overridden)));
    }

    @PutMapping("/{organizerUuid}")
    @Operation(summary = "Set an organizer's billing terms",
            description = "Create or replace the organizer's commission rate (0..100%) and billing cycle. The new "
                    + "rate applies to FUTURE invoices only — already-issued invoices keep their snapshotted rate.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Saved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Billing config saved",
                                      "data": { "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                                "commissionRate": 12.5000, "billingCycle": "MONTHLY",
                                                "currency": "USD", "overridden": true }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "commissionRate out of range",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "commissionRate must be between 0 and 100.", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<BillingConfigResponse>> upsert(@PathVariable UUID organizerUuid,
                                                                   @Valid @RequestBody BillingConfigRequest request) {
        OrganizerBillingConfig saved = billingConfigService.upsert(
                organizerUuid, request.commissionRate(), request.billingCycle(), request.currency());
        return ResponseEntity.ok(ApiResult.ok("Billing config saved", toResponse(saved, true)));
    }

    private static BillingConfigResponse toResponse(OrganizerBillingConfig c, boolean overridden) {
        return new BillingConfigResponse(c.getOrganizerUuid(), c.getCommissionRate(),
                c.getBillingCycle().name(), c.getCurrency(), overridden);
    }
}
