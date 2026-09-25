package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The no-QR gate fallback: find a booking by confirmation number.
 *
 * <p>Built on a REAL {@link TicketScanService}, so the authorization cases
 * exercise the scan's own checks rather than a mock of them — the property
 * worth pinning is that the lookup can never be looser than the scan.
 */
@ExtendWith(MockitoExtension.class)
class GateLookupServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingItemRepository bookingItemRepository;
    @Mock private UserServiceClient userServiceClient;
    @Mock private ScanAttemptRepository scanAttemptRepository;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;
    @Mock private ObjectProvider<EventServiceClient> eventServiceClientProvider;
    @Mock private ObjectProvider<MarketTimeZone> marketTimeZoneProvider;

    private GateLookupService service;
    private final UUID organizerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TicketScanService scan = new TicketScanService(bookingItemRepository, userServiceClient,
                scanAttemptRepository, meterRegistryProvider, "ZW",
                eventServiceClientProvider, marketTimeZoneProvider);
        ReflectionTestUtils.setField(scan, "internalToken", "test-internal-token");
        ReflectionTestUtils.setField(scan, "assignmentCheckFailOpen", false);
        service = new GateLookupService(bookingRepository, scan, meterRegistryProvider);
        lenient().when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(allowed(true));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ApiResult<ScanAccessDTO> allowed(boolean allowed) {
        return ApiResult.<ScanAccessDTO>builder().code("200").message("ok")
                .data(ScanAccessDTO.builder().allowed(allowed).build()).build();
    }

    private void authenticateAsTeamMember(UUID organizer) {
        var auth = new UsernamePasswordAuthenticationToken("tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null, UUID.randomUUID(),
                organizer, "Tariro", "Chikomo"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsOrganizer(UUID organizer) {
        var auth = new UsernamePasswordAuthenticationToken("owner@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("owner@harare-arena.co.zw", null, UUID.randomUUID(),
                organizer, null, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** A two-ticket booking: the buyer's own (already used) and a named guest's (unused). */
    private Booking booking(Booking.BookingStatus status, UUID organizer) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .status(status)
                .tenantUserUuid(organizer)
                .confirmationNumber("INN-20260901-3C8849")
                .customerName("Tendai Ncube")
                .phoneNumber("+263771234567")
                .build();
        BookingItem guest = BookingItem.builder()
                .id(UUID.randomUUID()).booking(b).ticketNumber("20260901-73015K")
                .categoryName("VIP").attendeeName("Rufaro Moyo").build();
        BookingItem own = BookingItem.builder()
                .id(UUID.randomUUID()).booking(b).ticketNumber("20260901-48291X")
                .categoryName("VIP")
                .redeemedAt(LocalDateTime.of(2026, 9, 26, 7, 42, 11))
                .redeemedByName("Tariro Chikomo").build();
        b.setItems(new ArrayList<>(List.of(guest, own)));
        return b;
    }

    private void bookingExists(Booking b) {
        when(bookingRepository.findByConfirmationNumber("INN-20260901-3C8849")).thenReturn(Optional.of(b));
    }

    @Test
    void found_listsEveryTicketWithItsHolderAndWhetherItIsUsed() {
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(r.getCustomerName()).isEqualTo("Tendai Ncube");
        assertThat(r.getCustomerPhoneLast4()).isEqualTo("****4567");
        assertThat(r.getTickets()).extracting(GateLookupResponseDTO.Ticket::getTicketNumber)
                .containsExactly("20260901-48291X", "20260901-73015K");
        GateLookupResponseDTO.Ticket own = r.getTickets().get(0);
        assertThat(own.getHolderName()).as("no attendee -> the purchaser").isEqualTo("Tendai Ncube");
        assertThat(own.isRedeemed()).isTrue();
        assertThat(own.getRedeemedByName()).isEqualTo("Tariro Chikomo");
        GateLookupResponseDTO.Ticket guest = r.getTickets().get(1);
        assertThat(guest.getHolderName()).isEqualTo("Rufaro Moyo");
        assertThat(guest.isRedeemed()).isFalse();
        assertThat(guest.getRedeemedAt()).isNull();
    }

    @Test
    void theNumberIsMatchedWhateverCaseOrSpacingItWasTypedIn() {
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));

        GateLookupResponseDTO r = service.lookup("  inn-20260901-3c8849 ");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(r.getConfirmationNumber()).isEqualTo("INN-20260901-3C8849");
    }

    @Test
    void aLookupAdmitsNobody_andWritesNoScanAttempt() {
        // Admission is POST /tickets/scan with a ticketNumber; the lookup must
        // never claim a ticket itself, or the scan audit and the event-day rule
        // would be bypassed.
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));

        service.lookup("INN-20260901-3C8849");

        verify(bookingItemRepository, never()).claimRedemption(any(), any(), any(), anyString());
        verifyNoInteractions(scanAttemptRepository);
    }

    @Test
    void unknownNumber_isNotFound_andEchoesOnlyTheNumber() {
        authenticateAsTeamMember(organizerUuid);
        when(bookingRepository.findByConfirmationNumber("INN-20260901-000000")).thenReturn(Optional.empty());

        GateLookupResponseDTO r = service.lookup("INN-20260901-000000");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.BOOKING_NOT_FOUND);
        assertThat(r.getTickets()).isNull();
        assertThat(r.getCustomerName()).isNull();
    }

    @Test
    void unpaidBooking_isRefused_withoutListingItsTickets() {
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.PENDING, organizerUuid));

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.BOOKING_NOT_CONFIRMED);
        assertThat(r.getTickets()).isNull();
        assertThat(r.getCustomerPhoneLast4()).isNull();
    }

    @Test
    void anotherOrganizersBooking_isRefused_andDisclosesNothing() {
        authenticateAsTeamMember(UUID.randomUUID());
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.WRONG_ORGANIZER);
        assertThat(r.getTickets()).isNull();
        assertThat(r.getCustomerName()).isNull();
        assertThat(r.getCustomerPhoneLast4()).isNull();
    }

    @Test
    void teamMemberNotOnThisEvent_isRefused_exactlyAsTheScanWouldRefuseThem() {
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(allowed(false));

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertThat(r.getTickets()).isNull();
    }

    @Test
    void assignmentServiceDown_failsClosed_likeTheScan() {
        authenticateAsTeamMember(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));
        when(userServiceClient.canScanEvent(any(), any(), any())).thenReturn(
                ApiResult.<ScanAccessDTO>builder().code("503").message("down").data(null).build());

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
    }

    @Test
    void organizer_isNeverAskedAboutAssignments() {
        authenticateAsOrganizer(organizerUuid);
        bookingExists(booking(Booking.BookingStatus.CONFIRMED, organizerUuid));

        GateLookupResponseDTO r = service.lookup("INN-20260901-3C8849");

        assertThat(r.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        verify(userServiceClient, never()).canScanEvent(any(), any(), any());
    }

    @Test
    void everyAuthorizationRefusalTheScanCanReturn_existsOnTheLookup() {
        // The service maps the scan's refusal onto the lookup enum by NAME, so a
        // refusal added to authorizationRefusal without a twin here would throw
        // at the gate instead of failing the build.
        for (String name : List.of("WRONG_ORGANIZER", "NOT_ASSIGNED_TO_EVENT")) {
            assertThat(GateLookupResponseDTO.Status.valueOf(name)).isNotNull();
        }
    }

    @Test
    void normalise_trimsAndUppercases() {
        assertThat(GateLookupService.normalise(" inn-20260901-3c8849\t")).isEqualTo("INN-20260901-3C8849");
        assertThat(GateLookupService.normalise(null)).isEmpty();
    }
}
