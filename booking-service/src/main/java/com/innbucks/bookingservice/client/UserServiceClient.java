package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(
        name = "user-service",
        fallback = UserServiceClientFallback.class)
public interface UserServiceClient {

    @GetMapping("/auth/customer/tier")
    ApiResult<CustomerTierResponseDTO> getCustomerTier(@RequestParam("phoneNumber") String phoneNumber);

    // S2S scan-authorization check: may this team member scan tickets for this
    // event? user-service encodes the assignment product rule (no assignments
    // = organizer-wide = allowed) in the `allowed` flag. Internal endpoint,
    // gated by X-Internal-Token and blocked at the gateway edge.
    @GetMapping("/users/internal/team-members/{teamMemberUuid}/can-scan/{eventId}")
    ApiResult<ScanAccessDTO> canScanEvent(
            @PathVariable("teamMemberUuid") UUID teamMemberUuid,
            @PathVariable("eventId") UUID eventId,
            @RequestHeader("X-Internal-Token") String internalToken);
}
