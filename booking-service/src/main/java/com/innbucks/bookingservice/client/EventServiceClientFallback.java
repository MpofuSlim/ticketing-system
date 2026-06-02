package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Returns a null payload on event-service failure. Booking creation stays
// possible even when event-service is down; the resulting booking just has
// tenantId=null, which means loyalty earn/redeem will be skipped at confirm
// time. Booking flow is never blocked by an event-service outage.
@Component
@Slf4j
public class EventServiceClientFallback implements EventServiceClient {

    @Override
    public ApiResult<EventLookupDTO> getEvent(java.util.UUID id) {
        log.warn("event-service circuit open or call failed (getEvent) eventId={}", id);
        return ApiResult.<EventLookupDTO>builder().code("503").message("event unavailable").data(null).build();
    }

    @Override
    public ApiResult<AvailabilityResponseDTO> consumeAvailability(java.util.UUID id, int count, String internalToken) {
        log.warn("event-service circuit open or call failed (consumeAvailability) eventId={} count={}",
                id, count);
        return ApiResult.<AvailabilityResponseDTO>builder()
                .code("503").message("event unavailable").data(null).build();
    }

    @Override
    public ApiResult<AvailabilityResponseDTO> releaseAvailability(java.util.UUID id, int count, String internalToken) {
        log.warn("event-service circuit open or call failed (releaseAvailability) eventId={} count={}",
                id, count);
        return ApiResult.<AvailabilityResponseDTO>builder()
                .code("503").message("event unavailable").data(null).build();
    }
}
