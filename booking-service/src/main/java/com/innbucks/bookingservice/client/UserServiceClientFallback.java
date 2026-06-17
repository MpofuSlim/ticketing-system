package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
    public ApiResult<ScanAccessDTO> canScanEvent(UUID teamMemberUuid, UUID eventId, String internalToken) {
        // Null data signals "couldn't determine" to TicketScanService, which
        // then applies its configured fail-open / fail-closed policy. We do
        // NOT fabricate allowed=true here — that decision belongs to the
        // scan service so the policy is in one place.
        log.warn("user-service circuit open or call failed (canScanEvent) teamMemberUuid={} eventId={}",
                teamMemberUuid, eventId);
        return ApiResult.<ScanAccessDTO>builder()
                .code("503")
                .message("user-service unavailable")
                .data(null)
                .build();
    }
}
