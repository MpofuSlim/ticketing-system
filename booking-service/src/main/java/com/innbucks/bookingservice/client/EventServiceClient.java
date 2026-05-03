package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(
        name = "event-service",
        url = "${event-service.url}",
        fallback = EventServiceClientFallback.class)
public interface EventServiceClient {

    @GetMapping("/events/{id}")
    ApiResult<EventLookupDTO> getEvent(@PathVariable("id") UUID id);
}
