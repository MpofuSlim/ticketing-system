package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.BookingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V22 — the purchaser's name and per-ticket attendees on {@code POST /bookings}.
 *
 * <p>Two contracts pinned here, both resolved in the controller before the
 * service runs:
 * <ul>
 *   <li><b>customerName</b>: body wins; an authenticated customer falls back
 *       to the JWT's first/last name; a guest with neither is a 400.</li>
 *   <li><b>attendee.phoneNumber</b>: validated and canonicalised to E.164
 *       exactly like the purchaser's — a malformed one is a 400 that names the
 *       attendee, and nothing is booked. Without this, the bad number would be
 *       stored and that guest's QR would fail silently at Twilio (63024).</li>
 * </ul>
 */
class BookingControllerAttendeeTest {

    private static BookingController controller(BookingService bookingService) {
        BookingController c = new BookingController(
                bookingService,
                mock(UserServiceClient.class),
                mock(com.innbucks.bookingservice.service.EventChangeNotificationService.class));
        ReflectionTestUtils.setField(c, "deploymentCountry", "ZW");
        return c;
    }

    private static BookingService acceptingService() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.createBooking(any(), any(), any()))
                .thenReturn(BookingResponseDTO.builder().id(UUID.randomUUID()).build());
        return bookingService;
    }

    private static CreateBookingRequestDTO guestRequest(String customerName) {
        CreateBookingRequestDTO req = new CreateBookingRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setCustomerName(customerName);
        req.setPhoneNumber("+263782606983");
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(UUID.randomUUID());
        req.setSeats(List.of(seat));
        return req;
    }

    private static CreateBookingRequestDTO.SeatItemRequest seatFor(String name, String email, String phone) {
        CreateBookingRequestDTO.AttendeeRequest a = new CreateBookingRequestDTO.AttendeeRequest();
        a.setFullName(name);
        a.setEmail(email);
        a.setPhoneNumber(phone);
        CreateBookingRequestDTO.SeatItemRequest s = new CreateBookingRequestDTO.SeatItemRequest();
        s.setCategoryId(UUID.randomUUID());
        s.setAttendee(a);
        return s;
    }

    /** A CUSTOMER token carrying a phone and a first/last name. */
    private static Authentication customerJwt(String first, String last) {
        var auth = new UsernamePasswordAuthenticationToken("alice@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new JwtAuthDetails("alice@example.com", "+263771234567",
                UUID.randomUUID(), null, first, last));
        return auth;
    }

    @Test
    void guest_withoutAName_isRefused_andNothingIsBooked() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);

        assertThatThrownBy(() -> controller.createBooking(guestRequest("   "), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("full name");

        verify(bookingService, never()).createBooking(any(), any(), any());
    }

    @Test
    void guest_name_isTrimmed_andPassedToTheService() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);

        controller.createBooking(guestRequest("  Alice Moyo "), null);

        ArgumentCaptor<CreateBookingRequestDTO> req = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(any(), any(), req.capture());
        assertThat(req.getValue().getCustomerName()).isEqualTo("Alice Moyo");
    }

    @Test
    void authenticatedCustomer_withoutAName_fallsBackToTheJwtName() {
        // Keeps the existing app flow working: no form change needed for a
        // signed-in customer whose profile already carries their name.
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);

        controller.createBooking(guestRequest(null), customerJwt("Alice", "Moyo"));

        ArgumentCaptor<CreateBookingRequestDTO> req = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(any(), any(), req.capture());
        assertThat(req.getValue().getCustomerName()).isEqualTo("Alice Moyo");
    }

    @Test
    void authenticatedCustomer_bodyName_winsOverTheJwtName() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);

        controller.createBooking(guestRequest("A. Moyo-Ncube"), customerJwt("Alice", "Moyo"));

        ArgumentCaptor<CreateBookingRequestDTO> req = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(any(), any(), req.capture());
        assertThat(req.getValue().getCustomerName()).isEqualTo("A. Moyo-Ncube");
    }

    @Test
    void authenticatedCustomer_withNoNameAnywhere_isRefused() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);

        assertThatThrownBy(() -> controller.createBooking(guestRequest(null), customerJwt(null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("full name");
        verify(bookingService, never()).createBooking(any(), any(), any());
    }

    @Test
    void malformedAttendeePhone_isRejectedNamingTheAttendee_andNothingIsBooked() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);
        CreateBookingRequestDTO req = guestRequest("Alice Moyo");
        CreateBookingRequestDTO.SeatItemRequest own = new CreateBookingRequestDTO.SeatItemRequest();
        own.setCategoryId(UUID.randomUUID());
        req.setSeats(List.of(
                own,
                seatFor("Tendai Ncube", null, "+26377200000"))); // one digit short

        assertThatThrownBy(() -> controller.createBooking(req, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Tendai Ncube")
                .hasMessageContaining("valid");

        verify(bookingService, never()).createBooking(any(), any(), any());
    }

    @Test
    void attendeePhone_isCanonicalisedToE164_andFieldsTrimmed_beforeTheService() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);
        CreateBookingRequestDTO req = guestRequest("Alice Moyo");
        req.setSeats(List.of(seatFor("  Tendai Ncube ", "  tendai@example.com ", "0772000000")));

        controller.createBooking(req, null);

        ArgumentCaptor<CreateBookingRequestDTO> captured = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(any(), any(), captured.capture());
        CreateBookingRequestDTO.AttendeeRequest a = captured.getValue().getSeats().get(0).getAttendee();
        assertThat(a.getFullName()).isEqualTo("Tendai Ncube");
        assertThat(a.getEmail()).isEqualTo("tendai@example.com");
        assertThat(a.getPhoneNumber()).isEqualTo("+263772000000");
    }

    @Test
    void attendeeWithNameOnly_isAccepted_blankContactBecomesNull() {
        BookingService bookingService = acceptingService();
        BookingController controller = controller(bookingService);
        CreateBookingRequestDTO req = guestRequest("Alice Moyo");
        req.setSeats(List.of(seatFor("Tendai Ncube", "  ", "")));

        controller.createBooking(req, null);

        ArgumentCaptor<CreateBookingRequestDTO> captured = ArgumentCaptor.forClass(CreateBookingRequestDTO.class);
        verify(bookingService).createBooking(any(), any(), captured.capture());
        CreateBookingRequestDTO.AttendeeRequest a = captured.getValue().getSeats().get(0).getAttendee();
        assertThat(a.getEmail()).isNull();
        assertThat(a.getPhoneNumber()).isNull();
    }
}
