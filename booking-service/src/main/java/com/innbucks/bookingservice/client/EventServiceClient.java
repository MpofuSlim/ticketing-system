package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(
        name = "event-service",
        fallback = EventServiceClientFallback.class)
public interface EventServiceClient {

    @GetMapping("/events/{id}")
    ApiResult<EventLookupDTO> getEvent(@PathVariable("id") UUID id);

    // Internal full lookup INCLUDING tenantUserUuid. The public GET
    // /events/{id} strips that field for anonymous callers (A01 organizer-
    // enumeration hardening) — and a server-side Feign call IS anonymous, so
    // every ownership check and the tenant capture at booking creation must
    // read through this endpoint instead. Same X-Internal-Token contract as
    // the availability calls; also returns draft/rejected events so ownership
    // checks keep working after an event is unpublished.
    @GetMapping("/events/internal/{id}")
    ApiResult<EventLookupDTO> getEventInternal(
            @PathVariable("id") UUID id,
            @RequestHeader("X-Internal-Token") String internalToken
    );

    // Decrements the event's stored availableTickets atomically. Called from
    // BookingService.confirmBooking once a booking transitions to CONFIRMED.
    //
    // event-service enforces the X-Internal-Token shared secret on this path
    // as defence in depth on top of the gateway's event-availability-deny
    // rule. The same value lives in `innbucks.internal-api-token` (env var
    // INTERNAL_API_TOKEN) on both ends — see docker-compose.yml.
    @PatchMapping("/events/{id}/availability/consume")
    ApiResult<AvailabilityResponseDTO> consumeAvailability(
            @PathVariable("id") UUID id,
            @RequestParam("count") int count,
            @RequestHeader("X-Internal-Token") String internalToken
    );

    // Inverse of consumeAvailability — increments the event's stored
    // availableTickets when a CONFIRMED booking is reversed (admin refund,
    // no-show, future real-payment failure compensation). Same X-Internal-Token
    // contract. Clamped to totalCapacity on the event-service side so a buggy
    // caller can't inflate available above the seat count; double-release
    // prevention lives on this side via Booking.availabilityReleased.
    @PatchMapping("/events/{id}/availability/release")
    ApiResult<AvailabilityResponseDTO> releaseAvailability(
            @PathVariable("id") UUID id,
            @RequestParam("count") int count,
            @RequestHeader("X-Internal-Token") String internalToken
    );
}
