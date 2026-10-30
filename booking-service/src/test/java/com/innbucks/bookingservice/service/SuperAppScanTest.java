package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.dto.ScannerAccessDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate scanners on the InnBucks super app: a CUSTOMER session whose authority
 * comes from event-service's scanner memberships, asked per call. Pins the
 * order the scan and the gate lookup check things in, that a phone not on the
 * team learns nothing and writes no scan row, that an outage is a 503 and never
 * an allow, and that the scanner app's own path is untouched apart from the
 * optional {@code eventId}.
 */
@ExtendWith(MockitoExtension.class)
class SuperAppScanTest {

    private static final String TICKET = "20261030-7KQ4M-9XZ2B";
    private static final String CONFIRMATION = "INN-20261030-3C8849";

    @Mock private BookingItemRepository bookingItemRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserServiceClient userServiceClient;
    @Mock private ScanAttemptRepository scanAttemptRepository;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;
    @Mock private ObjectProvider<EventServiceClient> eventServiceClientProvider;
    @Mock private ObjectProvider<MarketTimeZone> marketTimeZoneProvider;
    @Mock private EventServiceClient eventServiceClient;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID organizerUuid = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID scannerUserUuid = UUID.randomUUID();

    private TicketScanService scan;
    private GateLookupService lookup;

    @BeforeEach
    void setUp() {
        scan = new TicketScanService(bookingItemRepository, userServiceClient,
                scanAttemptRepository, meterRegistryProvider, "ZW",
                eventServiceClientProvider, marketTimeZoneProvider);
        ReflectionTestUtils.setField(scan, "internalToken", "test-internal-token");
        ReflectionTestUtils.setField(scan, "eventDayCheckEnabled", false);
        lookup = new GateLookupService(bookingRepository, scan, meterRegistryProvider);
        lenient().when(eventServiceClientProvider.getIfAvailable()).thenReturn(eventServiceClient);
        lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(meters);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ fixtures

    /** The super-app session: CUSTOMER only, subject = the phone, no names. */
    private void signInOnTheSuperApp() {
        var auth = new UsernamePasswordAuthenticationToken("+263772000111", null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new JwtAuthDetails(null, "+263772000111", scannerUserUuid, null, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void accessIs(ScannerAccessDTO access) {
        when(eventServiceClient.scannerAccess(scannerUserUuid, eventId, "test-internal-token"))
                .thenReturn(ApiResult.<ScannerAccessDTO>builder().code("200").message("ok").data(access).build());
    }

    private void onTheTeam() {
        accessIs(ScannerAccessDTO.builder().allowed(true).scannerId(UUID.randomUUID())
                .organizerUuid(organizerUuid).displayName("Rudo Sibanda").build());
    }

    private BookingItem ticket(UUID itsEventId, UUID itsOrganizer, Booking.BookingStatus status) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(itsEventId)
                .status(status)
                .tenantUserUuid(itsOrganizer)
                .confirmationNumber(CONFIRMATION)
                .customerName("Tendai Ncube")
                .build();
        BookingItem item = BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .ticketNumber(TICKET)
                .categoryName("VIP")
                .build();
        booking.setItems(new java.util.ArrayList<>(List.of(item)));
        return item;
    }

    private ScanAttempt onlyAuditRow() {
        ArgumentCaptor<ScanAttempt> row = ArgumentCaptor.forClass(ScanAttempt.class);
        verify(scanAttemptRepository).save(row.capture());
        return row.getValue();
    }

    // ------------------------------------------------------------------ routing

    @Test
    void routing_customerOnlyIsSuperApp_anOrganizerOrTeamRoleNeverIs_andNoRolesNeverIs() {
        assertThat(TicketScanService.isSuperAppScanner(auth("ROLE_CUSTOMER"))).isTrue();
        assertThat(TicketScanService.isSuperAppScanner(auth("ROLE_CUSTOMER", "ROLE_EVENT_ORGANIZER"))).isFalse();
        assertThat(TicketScanService.isSuperAppScanner(auth("ROLE_CUSTOMER", "ROLE_TEAM_MEMBER"))).isFalse();
        assertThat(TicketScanService.isSuperAppScanner(auth())).isFalse();
        assertThat(TicketScanService.isSuperAppScanner(null)).isFalse();
    }

    private static UsernamePasswordAuthenticationToken auth(String... roles) {
        return new UsernamePasswordAuthenticationToken("x", null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    // ------------------------------------------------------------------ scan

    @Test
    void scan_onTheTeam_redeemsAsTheNameTheOrganizerTyped_andAuditsTheEventWithNoEmail() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(eq(item.getId()), any(LocalDateTime.class),
                eq(scannerUserUuid), eq("Rudo Sibanda"))).thenReturn(1);

        ScanTicketResponseDTO result = scan.scan(TICKET, eventId, "+263772000111");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(result.getRedeemedByName()).isEqualTo("Rudo Sibanda");
        assertThat(result.getCategoryName()).isEqualTo("VIP");
        ScanAttempt row = onlyAuditRow();
        assertThat(row.getOutcome()).isEqualTo(ScanAttempt.Outcome.ALLOWED);
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getScannerUserUuid()).isEqualTo(scannerUserUuid);
        assertThat(row.getScannerOrganizerUuid()).isEqualTo(organizerUuid);
        assertThat(row.getScannerDisplayName()).isEqualTo("Rudo Sibanda");
        assertThat(row.getScannerEmail()).isNull();
    }

    @Test
    void scan_notOnTheTeam_readsNoTicket_writesNoRow_andIsCounted() {
        signInOnTheSuperApp();
        accessIs(ScannerAccessDTO.builder().allowed(false).build());

        ScanTicketResponseDTO result = scan.scan(TICKET, eventId, null);

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertThat(result.getBookingItemId()).isNull();
        verify(bookingItemRepository, never()).findByTicketNumberWithBooking(anyString());
        verify(scanAttemptRepository, never()).save(any());
        assertThat(meters.counter("tickets.scan.super_app_refused").count()).isEqualTo(1.0);
    }

    @Test
    void scan_accessCannotBeAsked_is503_andNothingIsReadOrRedeemed() {
        signInOnTheSuperApp();
        when(eventServiceClient.scannerAccess(any(), any(), any()))
                .thenReturn(ApiResult.<ScannerAccessDTO>builder().code("503").message("down").data(null).build());

        assertThatThrownBy(() -> scan.scan(TICKET, eventId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(bookingItemRepository, never()).findByTicketNumberWithBooking(anyString());
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_accessCallThrows_is503_too() {
        signInOnTheSuperApp();
        when(eventServiceClient.scannerAccess(any(), any(), any())).thenThrow(new RuntimeException("circuit open"));

        assertThatThrownBy(() -> scan.scan(TICKET, eventId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void scan_withoutAnEvent_is400_beforeAnythingIsAsked() {
        signInOnTheSuperApp();

        assertThatThrownBy(() -> scan.scan(TICKET, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(eventServiceClient, never()).scannerAccess(any(), any(), any());
    }

    @Test
    void scan_anotherEventsTicket_isWrongEvent_notRedeemed_andAuditedAtThisGate() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(UUID.randomUUID(), organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));

        ScanTicketResponseDTO result = scan.scan(TICKET, eventId, null);

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_EVENT);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
        ScanAttempt row = onlyAuditRow();
        assertThat(row.getOutcome()).isEqualTo(ScanAttempt.Outcome.WRONG_EVENT);
        assertThat(row.getEventId()).isEqualTo(eventId);
    }

    @Test
    void scan_aBookingStampedForAnotherOrganizer_isWrongEventToo() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, UUID.randomUUID(), Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));

        assertThat(scan.scan(TICKET, eventId, null).getStatus())
                .isEqualTo(ScanTicketResponseDTO.Status.WRONG_EVENT);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_unknownTicket_isNotFound_andAuditedAtThisGate() {
        signInOnTheSuperApp();
        onTheTeam();
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.empty());

        assertThat(scan.scan(TICKET, eventId, null).getStatus())
                .isEqualTo(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND);
        ScanAttempt row = onlyAuditRow();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getScannerDisplayName()).isEqualTo("Rudo Sibanda");
    }

    @Test
    void scan_unpaidBooking_isNotConfirmed_notRedeemed() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, organizerUuid, Booking.BookingStatus.PENDING);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));

        assertThat(scan.scan(TICKET, eventId, null).getStatus())
                .isEqualTo(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_secondScan_isAlreadyRedeemed_withTheCategory() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(0);

        ScanTicketResponseDTO result = scan.scan(TICKET, eventId, null);

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        assertThat(result.getCategoryName()).isEqualTo("VIP");
    }

    @Test
    void scan_neverAsksUserServiceForAnAssignment() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        scan.scan(TICKET, eventId, null);

        verify(userServiceClient, never()).canScanEvent(any(), any(), any());
    }

    // ------------------------------------------------------------------ scanner app, eventId given

    @Test
    void scannerApp_eventIdForAnotherEvent_isWrongEvent_afterTheTeamChecks_andNotRedeemed() {
        var auth = new UsernamePasswordAuthenticationToken("tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        UUID teamMember = UUID.randomUUID();
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null, teamMember, organizerUuid, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
        BookingItem item = ticket(UUID.randomUUID(), organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingItemRepository.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(ApiResult.<ScanAccessDTO>builder()
                .code("200").message("ok").data(ScanAccessDTO.builder().allowed(true).build()).build());

        ScanTicketResponseDTO result = scan.scan(TICKET, eventId, "Tariro Chikomo");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_EVENT);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
        verify(eventServiceClient, never()).scannerAccess(any(), any(), any());
        assertThat(onlyAuditRow().getEventId()).isEqualTo(eventId);
    }

    // ------------------------------------------------------------------ gate lookup

    @Test
    void lookup_onTheTeam_findsTheBooking() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByConfirmationNumberWithItems(CONFIRMATION))
                .thenReturn(Optional.of(item.getBooking()));

        GateLookupResponseDTO result = lookup.lookup(CONFIRMATION.toLowerCase(), eventId);

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(result.getTickets()).extracting(GateLookupResponseDTO.Ticket::getTicketNumber)
                .containsExactly(TICKET);
    }

    @Test
    void lookup_notOnTheTeam_readsNoBooking() {
        signInOnTheSuperApp();
        accessIs(ScannerAccessDTO.builder().allowed(false).build());

        GateLookupResponseDTO result = lookup.lookup(CONFIRMATION, eventId);

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertThat(result.getTickets()).isNull();
        verify(bookingRepository, never()).findByConfirmationNumberWithItems(anyString());
    }

    @Test
    void lookup_anotherEventsBooking_isWrongEvent_withNoTickets() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(UUID.randomUUID(), organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByConfirmationNumberWithItems(CONFIRMATION))
                .thenReturn(Optional.of(item.getBooking()));

        GateLookupResponseDTO result = lookup.lookup(CONFIRMATION, eventId);

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.WRONG_EVENT);
        assertThat(result.getTickets()).isNull();
        assertThat(result.getCustomerName()).isNull();
    }

    @Test
    void lookup_aBookingWithNoOrganizerStamp_isWrongEvent() {
        signInOnTheSuperApp();
        onTheTeam();
        BookingItem item = ticket(eventId, null, Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByConfirmationNumberWithItems(CONFIRMATION))
                .thenReturn(Optional.of(item.getBooking()));

        assertThat(lookup.lookup(CONFIRMATION, eventId).getStatus())
                .isEqualTo(GateLookupResponseDTO.Status.WRONG_EVENT);
    }

    @Test
    void lookup_withoutAnEvent_is400() {
        signInOnTheSuperApp();

        assertThatThrownBy(() -> lookup.lookup(CONFIRMATION, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void lookup_accessCannotBeAsked_is503() {
        signInOnTheSuperApp();
        when(eventServiceClient.scannerAccess(any(), any(), any()))
                .thenReturn(ApiResult.<ScannerAccessDTO>builder().code("503").message("down").data(null).build());

        assertThatThrownBy(() -> lookup.lookup(CONFIRMATION, eventId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(bookingRepository, never()).findByConfirmationNumberWithItems(anyString());
    }

    @Test
    void lookup_scannerApp_eventIdForAnotherEvent_isWrongEvent() {
        var auth = new UsernamePasswordAuthenticationToken("org@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("org@harare-arena.co.zw", null, organizerUuid, organizerUuid, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
        BookingItem item = ticket(UUID.randomUUID(), organizerUuid, Booking.BookingStatus.CONFIRMED);
        when(bookingRepository.findByConfirmationNumberWithItems(CONFIRMATION))
                .thenReturn(Optional.of(item.getBooking()));

        assertThat(lookup.lookup(CONFIRMATION, eventId).getStatus())
                .isEqualTo(GateLookupResponseDTO.Status.WRONG_EVENT);
        assertThat(lookup.lookup(CONFIRMATION, null).getStatus())
                .isEqualTo(GateLookupResponseDTO.Status.FOUND);
    }
}
