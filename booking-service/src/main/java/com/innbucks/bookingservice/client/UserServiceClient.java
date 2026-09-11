package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.dto.TenantLookupRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "user-service",
        fallback = UserServiceClientFallback.class)
public interface UserServiceClient {

    @GetMapping("/auth/customer/tier")
    ApiResult<CustomerTierResponseDTO> getCustomerTier(@RequestParam("phoneNumber") String phoneNumber);

    // S2S scan-authorization check: may this team member scan tickets for this
    // event? user-service encodes the assignment rule in the `allowed` flag,
    // and that rule is DENY-BY-DEFAULT: true only when an assignment row exists
    // for this exact (member, event) pair, so a member with no assignments
    // scans nothing. Internal endpoint, gated by X-Internal-Token and blocked
    // at the gateway edge.
    @GetMapping("/users/internal/team-members/{teamMemberUuid}/can-scan/{eventId}")
    ApiResult<ScanAccessDTO> canScanEvent(
            @PathVariable("teamMemberUuid") UUID teamMemberUuid,
            @PathVariable("eventId") UUID eventId,
            @RequestHeader("X-Internal-Token") String internalToken);

    // S2S organizer business-contact lookup (for emailing commission invoices).
    // Same internal endpoint event-service uses to enrich events; gated by
    // X-Internal-Token and blocked at the gateway edge.
    @PostMapping("/users/internal/tenants/lookup-by-uuid")
    ApiResult<List<TenantContactDTO>> lookupTenants(
            @RequestBody TenantLookupRequest request,
            @RequestHeader("X-Internal-Token") String internalToken);
}
