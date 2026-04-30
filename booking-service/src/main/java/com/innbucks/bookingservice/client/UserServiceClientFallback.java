package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback when user-service is unreachable. We can't enforce a tier requirement
 * without a fresh tier reading, so callers should treat a null envelope as a
 * lookup failure and surface it as a 503 rather than silently allowing the
 * request through.
 */
@Component
@Slf4j
public class UserServiceClientFallback implements UserServiceClient {

    @Override
    public ApiResult<CustomerTierResponseDTO> lookupCustomerTier(String subject) {
        log.warn("user-service unavailable; tier lookup fallback engaged subject={}", subject);
        return null;
    }
}
