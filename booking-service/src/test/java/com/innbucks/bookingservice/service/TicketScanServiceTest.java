package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the four contracts the ticket-scan flow must enforce:
 *
 * <ol>
 *   <li><b>Single-shot redemption</b> — the second scan returns
 *       ALREADY_REDEEMED with the first scanner's audit fields,
 *       not a second ALLOWED.</li>
 *   <li><b>Organizer authorization</b> — a TEAM_MEMBER from a
 *       different organizer cannot scan a ticket and gets
 *       WRONG_ORGANIZER.</li>
 *   <li><b>Booking status gate</b> — a PENDING / CANCELLED booking
 *       is not redeemable; returns BOOKING_NOT_CONFIRMED.</li>
 *   <li><b>Unknown ticket</b> — TICKET_NOT_FOUND, no exception.</li>
 * </ol>
 *
 * <p>Pure Mockito — the atomic UPDATE behaviour is modelled by the
 * mock returning 1 for the first call and 0 for the second.
 */
@ExtendWith(MockitoExtension.class)
class TicketScanServiceTest {

    @Mock private BookingItemRepository bookingItemRepository;

    @InjectMocks private TicketScanService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email, UUID userUuid, UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(email, null);
        auth.setDetails(new JwtAuthDetails(email, null, userUuid, organizerUuid));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private BookingItem confirmedItem(UUID organizerUuid) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED)
                .tenantUserUuid(organizerUuid)
                .tenantId("organizer@example.com")
                .build();
        return BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .ticketNumber("20260619-48291X")
                .build();
    }

    @Test
    void scan_allowed_writesAuditFieldsAndReportsScannerName() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(eq(item.getId()),
                any(LocalDateTime.class), eq(scannerUuid), eq("tariro@harare-arena.co.zw")))
                .thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(result.getTicketNumber()).isEqualTo("20260619-48291X");
        assertThat(result.getBookingItemId()).isEqualTo(item.getId());
        assertThat(result.getRedeemedByName()).isEqualTo("tariro@harare-arena.co.zw");
        assertThat(result.getRedeemedAt()).isNotNull();
    }

    @Test
    void scan_secondCall_returnsAlreadyRedeemedWithOriginalScannerDetails() {
        // First scan landed earlier; we model the atomic UPDATE returning 0
        // and the row re-read showing the first scanner's details. The
        // RETRY scanner's name in the response must be the FIRST scanner,
        // not the retrier — that's the audit-trail invariant the
        // denormalised redeemed_by_name column buys us.
        UUID organizerUuid = UUID.randomUUID();
        UUID retryUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        LocalDateTime firstScanAt = LocalDateTime.of(2026, 6, 19, 19, 42, 11);
        item.setRedeemedAt(firstScanAt);
        item.setRedeemedByName("Tariro Chikomo");
        item.setRedeemedByUserUuid(UUID.randomUUID());

        authenticateAs("rufaro@harare-arena.co.zw", retryUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any()))
                .thenReturn(0);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "rufaro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        assertThat(result.getRedeemedByName()).isEqualTo("Tariro Chikomo");
        assertThat(result.getRedeemedAt()).isEqualTo(firstScanAt);
    }

    @Test
    void scan_wrongOrganizer_returnsWrongOrganizerWithoutWriting() {
        // A team-member from a different organizer's tree must not be
        // able to redeem this ticket; the atomic UPDATE is never even
        // attempted — the authorization check short-circuits first.
        UUID bookingOrganizer = UUID.randomUUID();
        UUID otherOrganizer = UUID.randomUUID();
        BookingItem item = confirmedItem(bookingOrganizer);
        authenticateAs("intruder@other-org.co.zw", UUID.randomUUID(), otherOrganizer);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "intruder@other-org.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_ORGANIZER);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_pendingBooking_returnsBookingNotConfirmed() {
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        item.getBooking().setStatus(Booking.BookingStatus.PENDING);
        authenticateAs("organizer@example.com", UUID.randomUUID(), organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "organizer@example.com");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_unknownTicket_returnsTicketNotFound() {
        authenticateAs("organizer@example.com", UUID.randomUUID(), UUID.randomUUID());
        when(bookingItemRepository.findByTicketNumberWithBooking("BOGUS-12345"))
                .thenReturn(Optional.empty());

        ScanTicketResponseDTO result = service.scan("BOGUS-12345", "organizer@example.com");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND);
        assertThat(result.getBookingItemId()).isNull();
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_legacyBookingWithoutTenantUuid_allowsOrganizerByEmail() {
        // Pre-V13 booking: tenantUserUuid is null. The scan path must
        // still allow the EVENT_ORGANIZER (whose email matches the
        // legacy tenantId) — but TEAM_MEMBERs are blocked for these
        // rows until backfill catches up.
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        item.getBooking().setTenantUserUuid(null);
        item.getBooking().setTenantId("organizer@example.com");
        authenticateAs("organizer@example.com", UUID.randomUUID(), organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "organizer@example.com");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
    }
}
