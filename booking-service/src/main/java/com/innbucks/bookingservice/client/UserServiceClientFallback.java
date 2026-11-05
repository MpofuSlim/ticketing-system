package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.dto.TenantLookupRequest;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

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

    @Override
    public ApiResult<List<TenantContactDTO>> lookupTenants(TenantLookupRequest request, String internalToken) {
        // Null data → the invoice notifier logs + skips the email (invoice still
        // issued). We don't fabricate a contact.
        log.warn("user-service circuit open or call failed (lookupTenants) count={}",
                request == null || request.userUuids() == null ? 0 : request.userUuids().size());
        return ApiResult.<List<TenantContactDTO>>builder()
                .code("503")
                .message("user-service unavailable")
                .data(null)
                .build();
    }
}
