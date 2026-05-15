package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(
        name = "event-service",
        url = "${event-service.url}",
        fallback = EventServiceClientFallback.class)
public interface EventServiceClient {

    @GetMapping("/events/{id}")
    ApiResult<EventLookupDTO> getEvent(@PathVariable("id") UUID id);

    // Decrements the event's stored availableTickets atomically. Called from
    // BookingService.confirmBooking once a booking transitions to CONFIRMED.
    @PatchMapping("/events/{id}/availability/consume")
    ApiResult<AvailabilityResponseDTO> consumeAvailability(
            @PathVariable("id") UUID id,
            @RequestParam("count") int count
    );
}
