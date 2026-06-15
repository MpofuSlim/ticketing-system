package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the booking phone-number validation added to stop malformed MSISDNs
 * reaching WhatsApp and failing at Twilio with error 63024 ("invalid
 * recipient") long after the booking is confirmed. A bad number must be
 * rejected at creation with a 400, before any booking is persisted.
 */
class BookingControllerPhoneValidationTest {

    private static BookingController controller(BookingService bookingService) {
        BookingController c = new BookingController(
                bookingService,
                mock(UserServiceClient.class),
                mock(com.innbucks.bookingservice.service.EventChangeNotificationService.class));
        // No Spring context in a unit test, so the @Value cell country is null;
        // set it so +-less numbers parse against ZW (matches the ZW cell).
        ReflectionTestUtils.setField(c, "deploymentCountry", "ZW");
        return c;
    }

    private static CreateBookingRequestDTO bookingWithPhone(String phone) {
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setPhoneNumber(phone);
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(UUID.randomUUID());
        req.setSeats(List.of(seat));
        return req;
    }

    @Test
    void malformedPhone_isRejectedWith400_andNeverReachesTheService() {
        BookingService bookingService = mock(BookingService.class);
        BookingController controller = controller(bookingService);

        // The production bug: 11-digit ZW number (a digit short of +263782606983).
        assertThatThrownBy(() -> controller.createBooking(
                bookingWithPhone("+26378260983"), null, mock(HttpServletRequest.class)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("valid");

        // Nothing was booked — validation blocks before the service call.
        verify(bookingService, never()).createBooking(any(), anyInt(), any(), any());
    }

    @Test
    void validPhone_isCanonicalisedToE164_beforeReachingTheService() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.createBooking(any(), anyInt(), any(), any()))
                .thenReturn(BookingResponseDTO.builder().id(UUID.randomUUID()).build());
        BookingController controller = controller(bookingService);

        // Local form, no '+': must be normalised to +263782606983 using the
        // ZW cell region before it's stored / sent to WhatsApp.
        controller.createBooking(bookingWithPhone("0782606983"), null, mock(HttpServletRequest.class));

        org.mockito.ArgumentCaptor<String> phone = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bookingService).createBooking(any(), anyInt(), phone.capture(), any());
        assertThat(phone.getValue()).isEqualTo("+263782606983");
    }
}
