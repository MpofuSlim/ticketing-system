package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.PublicBookingResponseDTO;
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
 * <p>Pins the contract: returns the trimmed, PII-free
 * {@link PublicBookingResponseDTO} (no userEmail / phoneNumber fields at all),
 * requires NO authentication (the UUID is the bearer credential — same model
 * as {@code /bookings/confirmation/{number}}), does NOT call the
 * ownership-scoped service path, and 404s on unknown ids.
 */
class BookingControllerPublicLookupTest {

    private static BookingController controller(BookingService bookingService) {
        return new BookingController(
                bookingService,
                mock(UserServiceClient.class),
                mock(EventChangeNotificationService.class));
    }

    private static PublicBookingResponseDTO bookingDto(UUID id) {
        return PublicBookingResponseDTO.builder()
                .id(id)
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

        ResponseEntity<ApiResult<PublicBookingResponseDTO>> resp =
                controller(bookingService).getBookingByIdPublic(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().getId()).isEqualTo(id);
        assertThat(resp.getBody().getData().getConfirmationNumber()).isEqualTo("INN-20260615-AB12CD");
        // Must be uncacheable: this is the FE's payment-polling target and its
        // body transitions PENDING -> CONFIRMED. A cached PENDING snapshot would
        // strand the customer on "awaiting confirmation", so the response pins
        // Cache-Control: no-store. Regression-guard the header here.
        assertThat(resp.getHeaders().getCacheControl()).contains("no-store");
        // PII-free by construction: the trimmed DTO has no email/phone/tenant
        // fields at all (compile-time guarantee, asserted here for intent).
        assertThat(PublicBookingResponseDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("userEmail", "phoneNumber", "tenantId", "pointsUsed", "cashAmount");

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
