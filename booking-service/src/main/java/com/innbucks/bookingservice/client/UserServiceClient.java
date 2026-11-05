package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.dto.TenantLookupRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(
        name = "user-service",
        fallback = UserServiceClientFallback.class)
public interface UserServiceClient {

    @GetMapping("/auth/customer/tier")
    ApiResult<CustomerTierResponseDTO> getCustomerTier(@RequestParam("phoneNumber") String phoneNumber);

    // S2S organizer business-contact lookup (for emailing commission invoices).
    // Same internal endpoint event-service uses to enrich events; gated by
    // X-Internal-Token and blocked at the gateway edge.
    @PostMapping("/users/internal/tenants/lookup-by-uuid")
    ApiResult<List<TenantContactDTO>> lookupTenants(
            @RequestBody TenantLookupRequest request,
            @RequestHeader("X-Internal-Token") String internalToken);
}
