package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the public {@code GET /bookings/public/{id}} lookup.
 *
 * <p>Pins the contract: returns the same {@link BookingResponseDTO} shape as
 * the authenticated lookup, requires NO authentication (the UUID is the
 * bearer credential — same model as {@code /bookings/confirmation/{number}}),
 * does NOT call the ownership-scoped service path, and 404s on unknown ids.
 */
class BookingControllerPublicLookupTest {

    private static BookingController controller(BookingService bookingService) {
        return new BookingController(
                bookingService,
                mock(UserServiceClient.class),
                mock(EventChangeNotificationService.class));
    }

    private static BookingResponseDTO bookingDto(UUID id) {
        return BookingResponseDTO.builder()
                .id(id)
                .userEmail("alice@example.com")
                .confirmationNumber("INN-20260615-AB12CD")
                .status(com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00"))
                .build();
    }

    @Test
    void publicLookup_returnsTheBooking_withoutCallingTheOwnershipScopedPath() {
        BookingService bookingService = mock(BookingService.class);
        UUID id = UUID.randomUUID();
        when(bookingService.getBookingByIdPublic(id)).thenReturn(bookingDto(id));

        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller(bookingService).getBookingByIdPublic(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().getId()).isEqualTo(id);
        assertThat(resp.getBody().getData().getConfirmationNumber()).isEqualTo("INN-20260615-AB12CD");

        // The ownership-scoped variant must NOT be called — that's the whole
        // point of the public path (would 403 a non-owner / NPE without an auth).
        verify(bookingService).getBookingByIdPublic(eq(id));
        verify(bookingService, never()).getBookingById(eq(id), org.mockito.ArgumentMatchers.anyString());
        verify(bookingService, never()).getBookingById(eq(id), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void publicLookup_unknownId_propagatesNotFoundFromService() {
        BookingService bookingService = mock(BookingService.class);
        UUID id = UUID.randomUUID();
        when(bookingService.getBookingByIdPublic(id))
                .thenThrow(new NotFoundException("Booking not found"));

        // GlobalExceptionHandler maps NotFoundException -> 404; here we just
        // assert the service exception propagates uncaught from the controller.
        assertThatThrownBy(() -> controller(bookingService).getBookingByIdPublic(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }
}
