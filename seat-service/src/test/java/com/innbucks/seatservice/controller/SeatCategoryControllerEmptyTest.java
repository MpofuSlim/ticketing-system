package com.innbucks.seatservice.controller;

import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.service.SeatCategoryAnalyticsService;
import com.innbucks.seatservice.service.SeatCategoryService;
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
 * Regression guard: an event with no seat categories must return 200 + empty
 * list, never 404. See the fleet-wide 404-on-empty fix.
 */
class SeatCategoryControllerEmptyTest {

    @Test
    void getByEvent_empty_returns200_notNotFound() {
        SeatCategoryService cat = mock(SeatCategoryService.class);
        when(cat.getCategoriesByEvent(any())).thenReturn(List.of());

        ResponseEntity<ApiResult<List<CreateCategoryResponseDTO>>> resp =
                new SeatCategoryController(cat, mock(SeatCategoryAnalyticsService.class))
                        .getByEvent(UUID.randomUUID());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getData().isEmpty());
    }
}
