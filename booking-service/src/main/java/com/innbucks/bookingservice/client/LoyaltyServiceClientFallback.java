package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import com.innbucks.bookingservice.dto.LoyaltyRedeemRequest;
import com.innbucks.bookingservice.dto.LoyaltyRuleResponse;
import com.innbucks.bookingservice.exception.LoyaltyServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

// When loyalty-service is unreachable:
//   - getRule() returns null; BookingService treats that as "no rule, skip
//     loyalty" and proceeds with pure cash.
//   - redeem() and earn() throw. redeem failure is rethrown by BookingService as
//     a clean 503; earn failure is caught there and queued for retry. earn must
//     throw (not return null) so both the confirm path and the retry job detect
//     failure by exception rather than silently treating a degraded call as done.
@Component
@Slf4j
public class LoyaltyServiceClientFallback implements LoyaltyServiceClient {

    @Override
    public LoyaltyRuleResponse getRule(UUID organizerUuid, String internalToken) {
        log.warn("loyalty-service circuit open or call failed (getRule) organizerUuid={}", organizerUuid);
        return null;
    }

    @Override
    public void redeem(LoyaltyRedeemRequest request, String internalToken) {
        log.warn("loyalty-service circuit open or call failed (redeem) reference={}", request.getReference());
        throw new LoyaltyServiceUnavailableException(
                "Loyalty service unavailable; cannot redeem points right now");
    }

    @Override
    public void earn(LoyaltyEarnRequest request, String internalToken) {
        log.warn("loyalty-service circuit open or call failed (earn) reference={}", request.getReference());
        throw new LoyaltyServiceUnavailableException(
                "Loyalty service unavailable; cannot credit points right now");
    }
}
