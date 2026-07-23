package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TicketResendResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.TicketDeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the manual-resend wiring: only CONFIRMED bookings, organizer must own
 * the booking's event (via the tenantUserUuid stamp), SUPER_ADMIN bypasses
 * ownership, and the per-channel outcome is surfaced verbatim so the dashboard
 * can show what actually went out.
 */
class TicketResendControllerTest {

    private static UsernamePasswordAuthenticationToken organizerAuth(UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(
                "organizer@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("organizer@example.com", null,
                UUID.randomUUID(), organizerUuid, "Rumbi", "Sibanda"));
        return auth;
    }

    private static UsernamePasswordAuthenticationToken superAdminAuth() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin@innbucks.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new JwtAuthDetails("admin@innbucks.co.zw", null,
                UUID.randomUUID(), null, "Super", "Admin"));
        return auth;
    }

    private static Booking confirmedBooking(UUID tenantUserUuid) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        b.setTenantUserUuid(tenantUserUuid);
        b.setConfirmationNumber("INN-20260723-09BB20");
        b.setUserEmail("customer@example.com");
        b.setPhoneNumber("+263770000001");
        return b;
    }

    @Test
    void owningOrganizer_resends_andGetsPerChannelOutcome() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        UUID organizerUuid = UUID.randomUUID();
        Booking booking = confirmedBooking(organizerUuid);
        when(repo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));
        when(delivery.deliver(booking)).thenReturn(
                new TicketDeliveryService.Outcome(true, true, true, 2, 2));

        ResponseEntity<ApiResult<TicketResendResponseDTO>> resp =
                new TicketResendController(repo, delivery)
                        .resendTicket(organizerAuth(organizerUuid), booking.getId());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        TicketResendResponseDTO dto = resp.getBody().getData();
        assertThat(dto.emailSent()).isTrue();
        assertThat(dto.qrTicketsSent()).isEqualTo(2);
        assertThat(dto.confirmationNumber()).isEqualTo("INN-20260723-09BB20");
        verify(delivery).deliver(booking);
    }

    @Test
    void superAdmin_resendsAnyBooking_ownershipBypassed() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        // Booking owned by some other organizer — admin may still resend.
        Booking booking = confirmedBooking(UUID.randomUUID());
        when(repo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));
        when(delivery.deliver(booking)).thenReturn(
                new TicketDeliveryService.Outcome(true, true, true, 1, 1));

        ResponseEntity<ApiResult<TicketResendResponseDTO>> resp =
                new TicketResendController(repo, delivery)
                        .resendTicket(superAdminAuth(), booking.getId());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(delivery).deliver(booking);
    }

    @Test
    void foreignOrganizer_isDenied_noDeliveryAttempted() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        Booking booking = confirmedBooking(UUID.randomUUID());
        when(repo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> new TicketResendController(repo, delivery)
                .resendTicket(organizerAuth(UUID.randomUUID()), booking.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("do not own");
        verify(delivery, never()).deliver(any());
    }

    @Test
    void nullTenantStamp_failsClosedForOrganizer() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        Booking booking = confirmedBooking(null);
        when(repo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> new TicketResendController(repo, delivery)
                .resendTicket(organizerAuth(UUID.randomUUID()), booking.getId()))
                .isInstanceOf(AccessDeniedException.class);
        verify(delivery, never()).deliver(any());
    }

    @Test
    void nonConfirmedBooking_isRejected_noDeliveryAttempted() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        UUID organizerUuid = UUID.randomUUID();
        Booking booking = confirmedBooking(organizerUuid);
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        when(repo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> new TicketResendController(repo, delivery)
                .resendTicket(organizerAuth(organizerUuid), booking.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CONFIRMED");
        verify(delivery, never()).deliver(any());
    }

    @Test
    void unknownBooking_is404() {
        BookingRepository repo = mock(BookingRepository.class);
        TicketDeliveryService delivery = mock(TicketDeliveryService.class);
        UUID id = UUID.randomUUID();
        when(repo.findByIdWithItems(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TicketResendController(repo, delivery)
                .resendTicket(superAdminAuth(), id))
                .isInstanceOf(NotFoundException.class);
        verify(delivery, never()).deliver(any());
    }
}
