package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailableSeatDTO;
import com.innbucks.bookingservice.dto.SeatLookupResponseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "seat-service",
        fallback = SeatServiceClientFallback.class)
public interface SeatServiceClient {

    @GetMapping("/seats/{id}/lookup")
    ApiResult<SeatLookupResponseDTO> lookupSeat(@PathVariable("id") UUID id);

    @GetMapping("/seats/available")
    ApiResult<List<AvailableSeatDTO>> getAvailableSeats(@RequestParam("categoryId") UUID categoryId);
}
