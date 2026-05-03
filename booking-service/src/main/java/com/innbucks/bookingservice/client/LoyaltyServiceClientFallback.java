package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import com.innbucks.bookingservice.dto.LoyaltyRedeemRequest;
import com.innbucks.bookingservice.dto.LoyaltyRuleResponse;
import com.innbucks.bookingservice.dto.LoyaltyTransactionResponse;
import com.innbucks.bookingservice.exception.LoyaltyServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// When loyalty-service is unreachable:
//   - getRule() returns null in the envelope; callers (BookingService) treat
//     that as "no rule available, skip loyalty" and proceed with pure cash.
//   - redeem() throws — a customer trying to pay with points must hard-fail
//     so they can fall back to cash, rather than silently confirming a
//     booking that was supposed to be paid in points.
//   - earn() also returns a null payload but no exception; failing to credit
//     points is annoying but should never block a booking the customer
//     already paid for.
@Component
@Slf4j
public class LoyaltyServiceClientFallback implements LoyaltyServiceClient {

    @Override
    public ApiResult<LoyaltyRuleResponse> getRule(String tenantId) {
        log.warn("loyalty-service circuit open or call failed (getRule) tenantId={}", tenantId);
        return ApiResult.<LoyaltyRuleResponse>builder().code("503").message("loyalty unavailable").data(null).build();
    }

    @Override
    public ApiResult<LoyaltyTransactionResponse> redeem(LoyaltyRedeemRequest request) {
        log.warn("loyalty-service circuit open or call failed (redeem) reference={}", request.getReference());
        throw new LoyaltyServiceUnavailableException(
                "Loyalty service unavailable; cannot redeem points right now");
    }

    @Override
    public ApiResult<LoyaltyTransactionResponse> earn(LoyaltyEarnRequest request) {
        log.warn("loyalty-service circuit open or call failed (earn) reference={}", request.getReference());
        return ApiResult.<LoyaltyTransactionResponse>builder().code("503").message("loyalty unavailable").data(null).build();
    }
}
