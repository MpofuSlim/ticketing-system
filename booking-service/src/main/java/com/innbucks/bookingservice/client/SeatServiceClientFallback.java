package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailableSeatDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.SeatLookupResponseDTO;
import com.innbucks.bookingservice.exception.SeatServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Invoked by Spring Cloud Circuit Breaker when calls to seat-service fail or
 * the breaker is open. Booking creation cannot proceed without seat data, so
 * we raise a typed exception rather than returning a degraded response — the
 * exception handler maps it to 503.
 */
@Component
@Slf4j
public class SeatServiceClientFallback implements SeatServiceClient {

    @Override
    public ApiResult<SeatLookupResponseDTO> lookupSeat(UUID id) {
        log.warn("seat-service unavailable; circuit breaker / fallback engaged seatId={}", id);
        throw new SeatServiceUnavailableException(
                "seat-service is currently unavailable. Please retry the booking shortly.");
    }

    @Override
    public ApiResult<CategoryLookupDTO> getCategory(UUID id) {
        log.warn("seat-service unavailable; circuit breaker / fallback engaged categoryId={}", id);
        throw new SeatServiceUnavailableException(
                "seat-service is currently unavailable. Please retry the booking shortly.");
    }

    @Override
    public ApiResult<List<AvailableSeatDTO>> getAvailableSeats(UUID categoryId, int limit) {
        log.warn("seat-service unavailable; circuit breaker / fallback engaged categoryId={}", categoryId);
        throw new SeatServiceUnavailableException(
                "seat-service is currently unavailable. Please retry the booking shortly.");
    }
}
