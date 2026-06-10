package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard: a customer with no bookings must get 200 + empty list from
 * GET /bookings/my, never 404. See the fleet-wide 404-on-empty fix.
 */
class BookingControllerEmptyTest {

    @Test
    void getMyBookings_empty_returns200_notNotFound() {
        BookingService svc = mock(BookingService.class);
        when(svc.getMyBookings(any())).thenReturn(List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("u@example.com");

        ResponseEntity<ApiResult<List<BookingResponseDTO>>> resp =
                new BookingController(svc, mock(UserServiceClient.class),
                        mock(com.innbucks.bookingservice.service.EventChangeNotificationService.class))
                        .getMyBookings(auth);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getData().isEmpty());
    }
}
