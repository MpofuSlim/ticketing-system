package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: a guest (unauthenticated, phone-only) booking must NOT call
 * user-service for a tier lookup.
 *
 * <p>The phone on a guest booking is client-supplied and won't resolve to a
 * registered customer, so the lookup is a doomed round trip whose answer we
 * already know (GUEST_TIER). On the booking hot path that's one failing
 * user-service call per booking — under load it saturates user-service and
 * trips this client's circuit breaker, which is exactly what the load test
 * surfaced. Guests must short-circuit straight to GUEST_TIER.
 */
class BookingControllerGuestTierTest {

    private static final int GUEST_TIER = 2;

    @Test
    void createBooking_guest_skipsUserServiceTierLookup() {
        BookingService bookingService = mock(BookingService.class);
        UserServiceClient userServiceClient = mock(UserServiceClient.class);
        when(bookingService.createBooking(any(), anyInt(), any(), any()))
                .thenReturn(BookingResponseDTO.builder().id(UUID.randomUUID()).build());

        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setPhoneNumber("+263770000001");
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(UUID.randomUUID());
        req.setSeats(List.of(seat));

        BookingController controller = new BookingController(bookingService, userServiceClient);

        // Guest path: authentication == null.
        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller.createBooking(req, null, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        // The whole point: user-service is never touched on the guest path.
        verify(userServiceClient, never()).getCustomerTier(any());
        // And the guest is booked at GUEST_TIER with the request's phone number.
        verify(bookingService).createBooking(isNull(), eq(GUEST_TIER), eq("+263770000001"), eq(req));
    }
}
