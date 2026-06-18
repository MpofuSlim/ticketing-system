package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import com.innbucks.bookingservice.dto.LoyaltyRedeemRequest;
import com.innbucks.bookingservice.dto.LoyaltyRuleResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// Ticket earn/redeem run on loyalty-service's S2S surface (organizer = merchant),
// gated by X-Internal-Token and denied at the gateway edge. The booking id is the
// idempotency reference; loyalty keeps the earn/redeem legs distinct internally.
@FeignClient(
        name = "loyalty-service",
        fallback = LoyaltyServiceClientFallback.class)
public interface LoyaltyServiceClient {

    @GetMapping("/loyalty/internal/ticketing/rule")
    LoyaltyRuleResponse getRule(@RequestParam("organizerUuid") UUID organizerUuid,
                                @RequestHeader("X-Internal-Token") String internalToken);

    @PostMapping("/loyalty/internal/ticketing/redeem")
    void redeem(@RequestBody LoyaltyRedeemRequest request,
                @RequestHeader("X-Internal-Token") String internalToken);

    @PostMapping("/loyalty/internal/ticketing/earn")
    void earn(@RequestBody LoyaltyEarnRequest request,
              @RequestHeader("X-Internal-Token") String internalToken);
}
