package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: a guest (unauthenticated, phone-only) booking must NOT call
 * user-service.
 *
 * <p>The phone on a guest booking is client-supplied and won't resolve to a
 * registered customer, so the lookup is a doomed round trip. On the booking hot
 * path that's one failing user-service call per booking — under load it
 * saturates user-service and trips this client's circuit breaker, which is
 * exactly what the load test surfaced. Guests must short-circuit.
 *
 * <p>The lookup used to fetch the caller's registration tier (guests were
 * defaulted to a GUEST_TIER constant). Tier no longer gates anything, so the
 * call survives only as an email fallback for authenticated callers — but the
 * skip-on-guest property this test pins is unchanged and still load-bearing.
 */
class BookingControllerGuestLookupTest {

    @Test
    void createBooking_guest_skipsUserServiceLookupEntirely() {
        BookingService bookingService = mock(BookingService.class);
        UserServiceClient userServiceClient = mock(UserServiceClient.class);
        when(bookingService.createBooking(any(), any(), any()))
                .thenReturn(BookingResponseDTO.builder().id(UUID.randomUUID()).build());

        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setPhoneNumber("+263770000001");
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(UUID.randomUUID());
        req.setSeats(List.of(seat));

        BookingController controller = new BookingController(bookingService, userServiceClient,
                mock(com.innbucks.bookingservice.service.EventChangeNotificationService.class));

        // Guest path: authentication == null.
        ResponseEntity<ApiResult<BookingResponseDTO>> resp = controller.createBooking(req, null);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        // The whole point: user-service is never touched on the guest path.
        verify(userServiceClient, never()).getCustomerTier(any());
        // The guest is booked with no email and the request's phone number —
        // and, crucially, with no tier argument to be gated on.
        verify(bookingService).createBooking(isNull(), eq("+263770000001"), eq(req));
    }
}
