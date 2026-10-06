package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End to end on real Postgres: the ticket scan, the gate lookup and the
 * SUPER_ADMIN reversal make their user-service / event-service calls with no
 * transaction open and no pooled connection bound to the calling thread — and
 * keep exactly the guarantees they had when one was.
 *
 * <p>Every remote client is a Mockito bean whose answer RECORDS what the
 * calling thread looked like at the moment of the call (transaction active?
 * any resource — the EntityManager / JDBC connection holder — bound?) and, for
 * the rows the request is about, proves from ANOTHER connection that the row
 * can be locked {@code FOR UPDATE NOWAIT} while the call is in flight.
 */
@TestPropertySource(properties = {
        "app.booking.reminder-cron=-",
        "app.booking.organizer-reminder-cron=-",
        "app.booking.expiration-poll-interval-ms=86400000",
        "app.loyalty-earn-retry.initial-delay-ms=86400000",
        "app.invoicing.scheduler-enabled=false",
        // The event-day rule is ON: its event-service lookup is one of the
        // calls under test.
        "innbucks.scan.event-day-check.enabled=true"
})
class RemoteCallsOutsideTransactionsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private UserServiceClient userServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private WhatsAppNotificationClient whatsApp;
    @MockitoBean private EmailNotificationClient email;
    @MockitoBean private SmsNotificationClient sms;

    @Autowired private TicketScanService ticketScanService;
    @Autowired private GateLookupService gateLookupService;
    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingItemRepository bookingItemRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private TransactionTemplate tx;
    private final UUID organizer = UUID.randomUUID();
    private final UUID teamMember = UUID.randomUUID();
    /** One line per remote call: "what|txActive|resourcesBound". */
    private final Queue<String> calls = new ConcurrentLinkedQueue<>();
    /** One entry per lock probe made from another connection during a call. */
    private final Queue<Boolean> lockedDuringCall = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM scan_attempts");
        jdbc.update("DELETE FROM booking_items");
        jdbc.update("DELETE FROM bookings");
    }

    // ---- scan ------------------------------------------------------------------

    @Test
    void teamMemberScan_asksBothServicesWithNoConnectionHeld_thenClaimsAndAuditsTogether() {
        Seeded s = seedConfirmed();
        when(userServiceClient.canScanEvent(eq(teamMember), eq(s.eventId), eq(internalToken))).thenAnswer(inv -> {
            record("user-service");
            probeItemLock(s.ticketNumber);
            return allowed(true);
        });
        when(eventServiceClient.getEventInternal(eq(s.eventId), eq(internalToken))).thenAnswer(inv -> {
            record("event-service");
            probeItemLock(s.ticketNumber);
            return onToday(s.eventId);
        });
        authenticateAsTeamMember();

        ScanTicketResponseDTO result = ticketScanService.scan(s.ticketNumber, "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALLOWED);
        assertThat(result.getHolderName()).isEqualTo("Rufaro Moyo");
        assertAllCallsOffTransaction(2);
        assertThat(lockedDuringCall).containsExactly(true, true);
        // The claim and its audit row committed together.
        assertThat(jdbc.queryForObject("SELECT redeemed_by_name FROM booking_items WHERE ticket_number = ?",
                String.class, s.ticketNumber)).isEqualTo("Tariro");
        assertThat(outcomes(s.ticketNumber)).containsExactly("ALLOWED");

        // Single-shot: the second scan is ALREADY_REDEEMED, naming the first
        // scanner, and audits exactly one more row.
        ScanTicketResponseDTO again = ticketScanService.scan(s.ticketNumber, "Someone Else");
        assertThat(again.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.ALREADY_REDEEMED);
        assertThat(again.getRedeemedByName()).isEqualTo("Tariro");
        assertThat(outcomes(s.ticketNumber)).containsExactlyInAnyOrder("ALLOWED", "ALREADY_REDEEMED");
    }

    @Test
    void scanRefusedByTheAssignmentCheck_commitsItsAuditRow_andRedeemsNothing() {
        Seeded s = seedConfirmed();
        when(userServiceClient.canScanEvent(any(), any(), anyString())).thenAnswer(inv -> {
            record("user-service");
            return allowed(false);
        });
        authenticateAsTeamMember();

        ScanTicketResponseDTO result = ticketScanService.scan(s.ticketNumber, "Tariro");

        assertThat(result.getStatus()).isEqualTo(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT);
        assertAllCallsOffTransaction(1);
        assertThat(outcomes(s.ticketNumber)).containsExactly("NOT_ASSIGNED_TO_EVENT");
        assertThat(jdbc.queryForObject("SELECT redeemed_at IS NULL FROM booking_items WHERE ticket_number = ?",
                Boolean.class, s.ticketNumber)).isTrue();
    }

    // ---- gate lookup -------------------------------------------------------

    @Test
    void gateLookup_asksUserServiceWithNoConnectionHeld() {
        Seeded s = seedConfirmed();
        when(userServiceClient.canScanEvent(eq(teamMember), eq(s.eventId), anyString())).thenAnswer(inv -> {
            record("user-service");
            return allowed(true);
        });
        authenticateAsTeamMember();

        GateLookupResponseDTO result = gateLookupService.lookup(s.confirmationNumber);

        assertThat(result.getStatus()).isEqualTo(GateLookupResponseDTO.Status.FOUND);
        assertThat(result.getTickets()).singleElement()
                .satisfies(t -> assertThat(t.getHolderName()).isEqualTo("Rufaro Moyo"));
        assertAllCallsOffTransaction(1);
    }

    // ---- reversal ------------------------------------------------------------

    @Test
    void reverse_releasesAtEventServiceWithTheBookingRowUnlocked_thenCancels() {
        Seeded s = seedConfirmed();
        when(eventServiceClient.releaseAvailability(eq(s.eventId), eq(1), eq(internalToken))).thenAnswer(inv -> {
            record("event-service");
            probeLock("SELECT true FROM bookings WHERE id = ? FOR UPDATE NOWAIT", s.bookingId);
            return ApiResult.ok("released", released());
        });

        BookingResponseDTO result = bookingService.reverseConfirmedBooking(s.bookingId, "admin@example.com");

        assertThat(result.getStatus()).isEqualTo(Booking.BookingStatus.CANCELLED);
        assertThat(result.getItems()).hasSize(1);
        assertAllCallsOffTransaction(1);
        assertThat(lockedDuringCall).containsExactly(true);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, s.bookingId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT availability_released FROM bookings WHERE id = ?",
                Boolean.class, s.bookingId)).isTrue();
    }

    @Test
    void reverse_whenTheBookingChangesDuringTheRelease_losesTheRaceAsBefore_andWritesNothing() {
        Seeded s = seedConfirmed();
        when(eventServiceClient.releaseAvailability(any(), anyInt(), anyString())).thenAnswer(inv -> {
            // A concurrent writer commits while the release is in flight. The
            // single-transaction version lost here at its version-checked
            // UPDATE; the phased one must lose the same way.
            jdbc.update("UPDATE bookings SET version = version + 1 WHERE id = ?", s.bookingId);
            return ApiResult.ok("released", released());
        });

        assertThatThrownBy(() -> bookingService.reverseConfirmedBooking(s.bookingId, "admin@example.com"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, s.bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT availability_released FROM bookings WHERE id = ?",
                Boolean.class, s.bookingId)).isFalse();
    }

    // ---- helpers -------------------------------------------------------------

    private record Seeded(UUID bookingId, UUID eventId, String ticketNumber, String confirmationNumber) {
    }

    private void record(String what) {
        calls.add(what
                + "|" + TransactionSynchronizationManager.isActualTransactionActive()
                + "|" + !TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    /** From another connection: can the ticket row be locked right now? */
    private void probeItemLock(String ticketNumber) {
        probeLock("SELECT true FROM booking_items WHERE ticket_number = ? FOR UPDATE NOWAIT", ticketNumber);
    }

    /**
     * Runs a {@code FOR UPDATE NOWAIT} in a REQUIRES_NEW transaction — always
     * a different connection from the caller's, whatever the caller holds —
     * and records whether the lock was granted.
     */
    private void probeLock(String sql, Object arg) {
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            lockedDuringCall.add(Boolean.TRUE.equals(other.execute(st ->
                    jdbc.queryForObject(sql, Boolean.class, arg))));
        } catch (DataAccessException lockNotAvailable) {
            lockedDuringCall.add(false);
        }
    }

    private void assertAllCallsOffTransaction(int expected) {
        assertThat(calls).hasSize(expected);
        assertThat(calls).allSatisfy(c -> {
            String[] parts = c.split("\\|");
            assertThat(parts[1]).as("transaction active during %s", c).isEqualTo("false");
            assertThat(parts[2]).as("resource bound during %s", c).isEqualTo("false");
        });
    }

    private List<String> outcomes(String ticketNumber) {
        return jdbc.queryForList("SELECT outcome FROM scan_attempts WHERE ticket_number = ?",
                String.class, ticketNumber);
    }

    private void authenticateAsTeamMember() {
        var auth = new UsernamePasswordAuthenticationToken("tariro@harare-arena.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails("tariro@harare-arena.co.zw", null, teamMember,
                organizer, "Tariro", "Chikomo"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ApiResult<ScanAccessDTO> allowed(boolean allowed) {
        return ApiResult.<ScanAccessDTO>builder().code("200").message("ok")
                .data(ScanAccessDTO.builder().allowed(allowed).build()).build();
    }

    private static ApiResult<EventLookupDTO> onToday(UUID eventId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return ApiResult.ok("ok", EventLookupDTO.builder()
                .eventId(eventId)
                .startDateTime(now.minusHours(1))
                .endDateTime(now.plusHours(2))
                .build());
    }

    private static AvailabilityResponseDTO released() {
        AvailabilityResponseDTO dto = new AvailabilityResponseDTO();
        dto.setAvailableTickets(10);
        return dto;
    }

    private Seeded seedConfirmed() {
        UUID eventId = UUID.randomUUID();
        String ticketNumber = "TKT-IT-" + UUID.randomUUID();
        String confirmationNumber = "INN-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID bookingId = tx.execute(st -> {
            Booking booking = bookingRepository.save(Booking.builder()
                    .userEmail("buyer@example.com")
                    .phoneNumber("+263771234567")
                    .customerName("Rufaro Moyo")
                    .eventId(eventId)
                    .confirmationNumber(confirmationNumber)
                    .status(Booking.BookingStatus.CONFIRMED)
                    .totalAmount(new BigDecimal("10.00"))
                    .tenantUserUuid(organizer)
                    .build());
            bookingItemRepository.save(BookingItem.builder()
                    .booking(booking)
                    .seatId(UUID.randomUUID())
                    .categoryId(UUID.randomUUID())
                    .rowLabel("A")
                    .seatNumber(1)
                    .categoryName("General")
                    .priceAtBooking(new BigDecimal("10.00"))
                    .ticketNumber(ticketNumber)
                    .isActive(true)
                    .build());
            return booking.getId();
        });
        return new Seeded(bookingId, eventId, ticketNumber, confirmationNumber);
    }
}
