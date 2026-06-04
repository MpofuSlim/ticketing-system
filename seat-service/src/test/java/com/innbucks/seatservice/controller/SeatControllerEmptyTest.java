package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.SeatResponseDTO;
import com.innbucks.seatservice.service.SeatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard: a category with no seats (or no available seats) must
 * return 200 + empty list, never 404. See the fleet-wide 404-on-empty fix.
 */
class SeatControllerEmptyTest {

    @Test
    void getSeatsByCategory_empty_returns200_notNotFound() {
        SeatService svc = mock(SeatService.class);
        when(svc.getSeatsByCategory(any())).thenReturn(List.of());

        ResponseEntity<ApiResult<List<SeatResponseDTO>>> resp =
                new SeatController(svc).getSeatsByCategory(UUID.randomUUID());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getData().isEmpty());
    }

    @Test
    void getAvailableSeats_empty_returns200_notNotFound() {
        SeatService svc = mock(SeatService.class);
        when(svc.getAvailableSeats(any())).thenReturn(List.of());

        ResponseEntity<ApiResult<List<SeatResponseDTO>>> resp =
                new SeatController(svc).getAvailableSeats(UUID.randomUUID());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getData().isEmpty());
    }
}
