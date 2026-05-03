package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import com.innbucks.bookingservice.dto.LoyaltyRedeemRequest;
import com.innbucks.bookingservice.dto.LoyaltyRuleResponse;
import com.innbucks.bookingservice.dto.LoyaltyTransactionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "loyalty-service",
        url = "${loyalty-service.url}",
        fallback = LoyaltyServiceClientFallback.class)
public interface LoyaltyServiceClient {

    @GetMapping("/loyalty/rules/{tenantId}")
    ApiResult<LoyaltyRuleResponse> getRule(@PathVariable("tenantId") String tenantId);

    @PostMapping("/loyalty/redeem")
    ApiResult<LoyaltyTransactionResponse> redeem(@RequestBody LoyaltyRedeemRequest request);

    @PostMapping("/loyalty/earn")
    ApiResult<LoyaltyTransactionResponse> earn(@RequestBody LoyaltyEarnRequest request);
}
