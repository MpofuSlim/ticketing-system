package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UserServiceClientFallback implements UserServiceClient {

    @Override
    public ApiResult<CustomerTierResponseDTO> getCustomerTier(String phoneNumber) {
        log.warn("user-service circuit open or call failed (getCustomerTier) phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        return ApiResult.<CustomerTierResponseDTO>builder()
                .code("503")
                .message("user-service unavailable")
                .data(null)
                .build();
    }
}
