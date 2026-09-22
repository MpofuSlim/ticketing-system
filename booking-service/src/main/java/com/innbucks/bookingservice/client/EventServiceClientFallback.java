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
    public ApiResult<EventLookupDTO> getEventInternal(java.util.UUID id, String internalToken) {
        log.warn("event-service circuit open or call failed (getEventInternal) eventId={}", id);
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

    // NULL data, deliberately NOT an empty list — and this is the one fallback
    // on this client where the distinction bills money. InvoiceService reads
    // empty as "asked, no events ended in that period" and null as "could not
    // ask", and only the first is allowed to produce invoices. An empty list
    // here would let a single failed call issue a period's invoices with the
    // ended events missing; the invoice is keyed (organizer, period) for
    // idempotency, so that under-billing could never be corrected by a later
    // run. Skipping instead costs a day and the next run picks the period up.
    @Override
    public ApiResult<java.util.List<java.util.UUID>> eventIdsEndedBetween(
            String from, String to, String internalToken) {
        log.warn("event-service circuit open or call failed (eventIdsEndedBetween) from={} to={} "
                + "— invoice generation for this period will be SKIPPED, not billed as empty", from, to);
        return ApiResult.<java.util.List<java.util.UUID>>builder()
                .code("503").message("event unavailable").data(null).build();
    }
}
