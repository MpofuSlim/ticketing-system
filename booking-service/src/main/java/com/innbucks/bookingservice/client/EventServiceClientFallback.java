package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Returns a null payload on event-service failure. Each caller decides what
// "could not ask" means for it: display reads degrade, while booking creation
// (salesWindow), super-app scans (scannerAccess) and sales agents' sales
// (sellerAccess) refuse with a retryable 503,
// because selling or admitting unchecked is worse than asking the buyer to
// retry.
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

    // Null data = "could not ask". createBooking refuses that with a retryable
    // 503: an outage is never read as "on sale".
    @Override
    public ApiResult<com.innbucks.bookingservice.dto.EventSalesWindowDTO> salesWindow(
            java.util.UUID eventId, String internalToken) {
        log.warn("event-service circuit open or call failed (salesWindow) eventId={}", eventId);
        return ApiResult.<com.innbucks.bookingservice.dto.EventSalesWindowDTO>builder()
                .code("503").message("event unavailable").data(null).build();
    }

    @Override
    public ApiResult<com.innbucks.bookingservice.dto.ScannerAccessDTO> scannerAccess(
            java.util.UUID userUuid, java.util.UUID eventId, String internalToken) {
        log.warn("event-service circuit open or call failed (scannerAccess) eventId={}", eventId);
        return ApiResult.<com.innbucks.bookingservice.dto.ScannerAccessDTO>builder()
                .code("503").message("event unavailable").data(null).build();
    }

    // Null data = "could not ask". A sales agent's sale refuses that with a
    // retryable 503: an outage is never read as "may sell".
    @Override
    public ApiResult<com.innbucks.bookingservice.dto.SellerAccessDTO> sellerAccess(
            java.util.UUID accountUserUuid, java.util.UUID eventId, String internalToken) {
        log.warn("event-service circuit open or call failed (sellerAccess) eventId={}", eventId);
        return ApiResult.<com.innbucks.bookingservice.dto.SellerAccessDTO>builder()
                .code("503").message("event unavailable").data(null).build();
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
