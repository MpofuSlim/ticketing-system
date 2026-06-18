package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the ticket-scan contracts. Inherits the #244 cases (single-shot
 * redemption, organizer authorization, status gate, unknown ticket) and
 * adds the per-event-assignment behaviour:
 *
 * <ul>
 *   <li>A TEAM_MEMBER not assigned to the event gets NOT_ASSIGNED_TO_EVENT.</li>
 *   <li>An EVENT_ORGANIZER bypasses the assignment check entirely.</li>
 *   <li>When user-service is unreachable, the configured fail-open / fail-closed
 *       policy decides.</li>
 * </ul>
 *
 * Pure Mockito. The atomic UPDATE is modelled by the repo returning 1 then 0;
 * the assignment check is modelled by the UserServiceClient mock.
 */
@ExtendWith(MockitoExtension.class)
class TicketScanServiceTest {

    @Mock private BookingItemRepository bookingItemRepository;
    @Mock private UserServiceClient userServiceClient;
    @Mock private ScanAttemptRepository scanAttemptRepository;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private TicketScanService service;

    @BeforeEach
    void setUp() {
        // Constructor injection so the new audit / meter dependencies land on
        // real fields rather than null-via-@InjectMocks fallback.
        service = new TicketScanService(bookingItemRepository, userServiceClient,
                scanAttemptRepository, meterRegistryProvider, "ZW");
        ReflectionTestUtils.setField(service, "internalToken", "test-internal-token");
        // Baseline mirrors the production default: fail CLOSED. The two
        // assignment-service-down cases set this field explicitly per-test.
        ReflectionTestUtils.setField(service, "assignmentCheckFailOpen", false);
        // Default: assignment check says "allowed" so the inherited cases that
        // authenticate as a bare team member still reach their intended status.
        // lenient() because the early-return cases (not-found, etc.) never call it.
        lenient().when(userServiceClient.canScanEvent(any(), any(), any()))
                .thenReturn(allowed(true));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ApiResult<ScanAccessDTO> allowed(boolean allowed) {
        return ApiResult.<ScanAccessDTO>builder()
                .code("200").message("ok")
                .data(ScanAccessDTO.builder().allowed(allowed).build())
                .build();
    }

    private ApiResult<ScanAccessDTO> serviceDown() {
        return ApiResult.<ScanAccessDTO>builder().code("503").message("down").data(null).build();
    }

    private void authenticateAs(String email, UUID userUuid, UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(email, null);
        auth.setDetails(new JwtAuthDetails(email, null, userUuid, organizerUuid, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsOrganizer(String email, UUID userUuid, UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails(email, null, userUuid, organizerUuid, null, null));
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
        assertThat(result.getRedeemedByName()).isEqualTo("tariro@harare-arena.co.zw");
        assertThat(result.getRedeemedAt()).isNotNull();
    }

    @Test
    void scan_guestBooking_allowed_whenTenantUserUuidMatchesScannerOrganizer() {
        // Pins the guest-checkout fix: a guest's CONFIRMED booking carries
        // tenantUserUuid (now captured from event-service even when userEmail
        // is null) but NO tenantId (event-service removed that field in V7).
        // scannerOwnsEvent must authorize the redeem on the uuid alone — the
        // dead email/tenantId fallback would otherwise short-circuit to false
        // and the gate would refuse the ticket with WRONG_ORGANIZER. Without
        // this test, CI didn't see the live guest-ticket shape because every
        // other fixture sets BOTH fields.
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        Booking guestBooking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED)
                .tenantUserUuid(organizerUuid)
                .userEmail(null)
                .tenantId(null)
                .build();
        BookingItem item = BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(guestBooking)
                .ticketNumber("20260619-48291X")
                .build();

        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(eq(item.getId()),
                any(LocalDateTime.class), eq(scannerUuid), eq("tariro@harare-arena.co.zw")))
                .thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
    }

    @Test
    void scan_secondCall_returnsAlreadyRedeemedWithOriginalScannerDetails() {
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        LocalDateTime firstScanAt = LocalDateTime.of(2026, 6, 19, 19, 42, 11);
        item.setRedeemedAt(firstScanAt);
        item.setRedeemedByName("Tariro Chikomo");
        item.setRedeemedByUserUuid(UUID.randomUUID());

        authenticateAs("rufaro@harare-arena.co.zw", UUID.randomUUID(), organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(0);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "rufaro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        assertThat(result.getRedeemedByName()).isEqualTo("Tariro Chikomo");
        assertThat(result.getRedeemedAt()).isEqualTo(firstScanAt);
    }

    @Test
    void scan_wrongOrganizer_returnsWrongOrganizerWithoutWriting() {
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
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_teamMemberNotAssignedToEvent_returnsNotAssigned() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        // user-service says this member is restricted and NOT assigned here.
        when(userServiceClient.canScanEvent(eq(scannerUuid), eq(item.getBooking().getEventId()), any()))
                .thenReturn(allowed(false));

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertThat(result.getBookingItemId()).isEqualTo(item.getId());
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_organizer_bypassesAssignmentCheckEntirely() {
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        // Organizer's own userUuid == organizerUuid; carries ROLE_EVENT_ORGANIZER.
        authenticateAsOrganizer("organizer@example.com", organizerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "organizer@example.com");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        // The assignment check must never be consulted for an organizer.
        verify(userServiceClient, never()).canScanEvent(any(), any(), any());
    }

    @Test
    void scan_assignmentServiceDown_failOpenAllowsWithinOrganizer() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        ReflectionTestUtils.setField(service, "assignmentCheckFailOpen", true);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(serviceDown());
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
    }

    @Test
    void scan_assignmentServiceDown_failClosedDenies() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        ReflectionTestUtils.setField(service, "assignmentCheckFailOpen", false);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(serviceDown());

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }
}
