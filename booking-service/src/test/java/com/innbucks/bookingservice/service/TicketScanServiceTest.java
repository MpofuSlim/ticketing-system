package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.ZoneOffset;
import java.time.ZoneId;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import org.mockito.ArgumentCaptor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private ObjectProvider<EventServiceClient> eventServiceClientProvider;
    @Mock private ObjectProvider<MarketTimeZone> marketTimeZoneProvider;
    @Mock private EventServiceClient eventServiceClient;

    private TicketScanService service;

    @BeforeEach
    void setUp() {
        // Constructor injection so the new audit / meter dependencies land on
        // real fields rather than null-via-@InjectMocks fallback.
        service = new TicketScanService(bookingItemRepository, userServiceClient,
                scanAttemptRepository, meterRegistryProvider, "ZW",
                eventServiceClientProvider, marketTimeZoneProvider);
        ReflectionTestUtils.setField(service, "internalToken", "test-internal-token");
        // Baseline mirrors the production default: fail CLOSED. The two
        // assignment-service-down cases set this field explicitly per-test.
        ReflectionTestUtils.setField(service, "assignmentCheckFailOpen", false);
        // The event-day rule is OFF for the inherited cases. @Value defaults are
        // a Spring concern, so a `new`-built service gets Java's boolean false
        // here anyway — setting it explicitly says that is intended rather than
        // incidental, and keeps these cases about the behaviour they were
        // written for. EventDayRuleTest covers the window arithmetic; the
        // block below covers the guard's wiring with the flag switched on.
        ReflectionTestUtils.setField(service, "eventDayCheckEnabled", false);
        ReflectionTestUtils.setField(service, "eventDayCheckFailOpen", false);
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
        // tenantUserUuid (captured from event-service even when userEmail is
        // null). scannerOwnsEvent authorizes the redeem on the uuid alone.
        // Without this test, CI didn't see the live guest-ticket shape because
        // the other fixtures all set userEmail.
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        Booking guestBooking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .status(Booking.BookingStatus.CONFIRMED)
                .tenantUserUuid(organizerUuid)
                .userEmail(null)
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

    // -- V22: who the ticket was issued to -----------------------------------

    @Test
    void scan_allowed_reportsTheNamedAttendeeAsHolder() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        item.getBooking().setCustomerName("Alice Moyo");
        item.setAttendeeName("Tendai Ncube");
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(result.getHolderName()).isEqualTo("Tendai Ncube");
    }

    @Test
    void scan_allowed_fallsBackToThePurchaser_whenNoAttendeeNamed() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        item.getBooking().setCustomerName("Alice Moyo");
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getHolderName()).isEqualTo("Alice Moyo");
    }

    @Test
    void scan_alreadyRedeemed_stillReportsTheHolder() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        item.setAttendeeName("Tendai Ncube");
        item.setRedeemedAt(LocalDateTime.of(2026, 6, 19, 19, 42));
        item.setRedeemedByName("Someone Else");
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(0);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        assertThat(result.getHolderName()).isEqualTo("Tendai Ncube");
    }

    @Test
    void scan_assignmentServiceDown_failClosedDenies() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        ReflectionTestUtils.setField(service, "assignmentCheckFailOpen", false);
        // The event-day rule is OFF for the inherited cases. @Value defaults are
        // a Spring concern, so a `new`-built service gets Java's boolean false
        // here anyway — setting it explicitly says that is intended rather than
        // incidental, and keeps these cases about the behaviour they were
        // written for. EventDayRuleTest covers the window arithmetic; the
        // block below covers the guard's wiring with the flag switched on.
        ReflectionTestUtils.setField(service, "eventDayCheckEnabled", false);
        ReflectionTestUtils.setField(service, "eventDayCheckFailOpen", false);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(serviceDown());

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    // ---- the event-day rule -------------------------------------------------
    //
    // EventDayRuleTest owns the window arithmetic (timezones, multi-day,
    // past-midnight). These cases own the GUARD's wiring: that it sits after
    // authorization and before the irreversible claim, that a refusal never
    // redeems, and that an unresolvable window obeys the fail-open flag.

    /** Turn the rule on and point it at an event with the given stored-UTC window. */
    private void enableEventDayRule(LocalDateTime startUtc, LocalDateTime endUtc) {
        ReflectionTestUtils.setField(service, "eventDayCheckEnabled", true);
        lenient().when(marketTimeZoneProvider.getIfAvailable()).thenReturn(new MarketTimeZone("ZW"));
        lenient().when(eventServiceClientProvider.getIfAvailable()).thenReturn(eventServiceClient);
        EventLookupDTO event = EventLookupDTO.builder()
                .startDateTime(startUtc)
                .endDateTime(endUtc)
                .build();
        lenient().when(eventServiceClient.getEventInternal(any(), any()))
                .thenReturn(ApiResult.ok("ok", event));
    }

    /** Rule on, but the window cannot be resolved (outage / open circuit). */
    private void enableEventDayRuleWithUnreachableEventService() {
        ReflectionTestUtils.setField(service, "eventDayCheckEnabled", true);
        lenient().when(marketTimeZoneProvider.getIfAvailable()).thenReturn(new MarketTimeZone("ZW"));
        lenient().when(eventServiceClientProvider.getIfAvailable()).thenReturn(eventServiceClient);
        lenient().when(eventServiceClient.getEventInternal(any(), any()))
                .thenThrow(new IllegalStateException("event-service unreachable"));
    }

    @Test
    void scan_onTheEventsDay_stillRedeems() {
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        // Window = today in the market, whatever today is when this runs.
        LocalDateTime todayUtc = LocalDateTime.now(ZoneOffset.UTC);
        enableEventDayRule(todayUtc, todayUtc);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
    }

    @Test
    void scan_onAnotherDay_isRefusedAndTheTicketIsNotRedeemed() {
        // THE point of the feature. The claim must never run — claimRedemption
        // has no inverse, so redeeming here would burn a ticket that is valid
        // on its own day.
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        LocalDateTime lastWeek = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        enableEventDayRule(lastWeek, lastWeek);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_EVENT_DAY);
        assertThat(result.getEventDate())
                .as("gate staff need the day the ticket IS for")
                .isEqualTo(lastWeek.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Africa/Harare")).toLocalDate());
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_offDay_isAudited() {
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", UUID.randomUUID(), organizerUuid);
        LocalDateTime lastWeek = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        enableEventDayRule(lastWeek, lastWeek);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        ArgumentCaptor<ScanAttempt> captor = ArgumentCaptor.forClass(ScanAttempt.class);
        verify(scanAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(ScanAttempt.Outcome.WRONG_EVENT_DAY);
    }

    @Test
    void scan_whenTheWindowIsUnresolvable_failsClosedWith503_andDoesNotRedeem() {
        // Fail CLOSED is the default. A 503 says "could not decide, retry" —
        // categorically not a verdict about the ticket, so the ticket is
        // neither redeemed nor audited as wrong-day.
        UUID organizerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", UUID.randomUUID(), organizerUuid);
        enableEventDayRuleWithUnreachableEventService();
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.scan("20260619-48291X", "tariro@harare-arena.co.zw"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), any());
    }

    @Test
    void scan_whenTheWindowIsUnresolvable_andFailOpenIsSet_letsTheScanThrough() {
        // The break-glass lever. Deliberately re-opens the gap, so it is pinned
        // rather than left as an untested branch.
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        enableEventDayRuleWithUnreachableEventService();
        ReflectionTestUtils.setField(service, "eventDayCheckFailOpen", true);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
    }

    @Test
    void scan_wrongOrganizer_isRefusedBeforeTheEventIsEverLookedUp() {
        // Ordering guard: the day rule sits AFTER authorization, so a scanner
        // who does not own the event cannot use it to probe the schedule.
        BookingItem item = confirmedItem(UUID.randomUUID());
        authenticateAs("intruder@elsewhere.co.zw", UUID.randomUUID(), UUID.randomUUID());
        LocalDateTime lastWeek = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        enableEventDayRule(lastWeek, lastWeek);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "intruder@elsewhere.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.WRONG_ORGANIZER);
        verify(eventServiceClient, never()).getEventInternal(any(), any());
    }

    @Test
    void scan_whenTheRuleIsDisabled_anyDayStillRedeems() {
        // The kill switch, pinned so nobody "tidies it away".
        UUID organizerUuid = UUID.randomUUID();
        UUID scannerUuid = UUID.randomUUID();
        BookingItem item = confirmedItem(organizerUuid);
        authenticateAs("tariro@harare-arena.co.zw", scannerUuid, organizerUuid);
        ReflectionTestUtils.setField(service, "eventDayCheckEnabled", false);
        when(bookingItemRepository.findByTicketNumberWithBooking("20260619-48291X"))
                .thenReturn(Optional.of(item));
        when(bookingItemRepository.claimRedemption(any(), any(), any(), any())).thenReturn(1);

        ScanTicketResponseDTO result = service.scan("20260619-48291X", "tariro@harare-arena.co.zw");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        verify(eventServiceClient, never()).getEventInternal(any(), any());
    }
}
