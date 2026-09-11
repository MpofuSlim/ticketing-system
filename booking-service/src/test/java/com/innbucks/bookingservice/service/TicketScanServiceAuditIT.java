package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression that pins the "every outcome writes exactly one scan_attempts
 * row" invariant. Pumps six scans through {@link TicketScanService#scan} on a
 * real Postgres (Testcontainers) and asserts six rows landed with the right
 * outcomes.
 *
 * <p>Why integration: the audit-write happens inside the {@code @Transactional}
 * boundary of the scan call. A unit test with mocks can prove
 * {@code repository.save(...)} was invoked, but only this end-to-end shape
 * proves the row actually reaches the database after the transaction commits.
 *
 * <p>Why the {@code it} profile: discovery is off and the {@code UserService}
 * Feign client resolves to its fallback (data=null, "service down"). For
 * ORGANIZER scans the assignment check is bypassed entirely; for the
 * NOT_ASSIGNED_TO_EVENT case we authenticate as a TEAM_MEMBER and rely on the
 * service's fail-CLOSED default to convert the "service down" response into a
 * NOT_ASSIGNED outcome — same path as production when user-service is
 * unreachable.
 */
@org.springframework.test.context.TestPropertySource(properties =
        // The six outcomes below predate the event-day rule and are about the
        // audit invariant, not about scheduling. Under the `it` profile every
        // Feign client resolves to its fallback, so the event lookup cannot
        // answer and the rule's fail-CLOSED default would 503 each scan before
        // it reached its intended outcome. Turning the rule off keeps these
        // cases testing what they were written to test; the rule's own
        // behaviour is pinned by TicketScanServiceTest (guard wiring) and
        // EventDayRuleTest (window arithmetic), and its DATABASE contract by
        // wrongEventDayOutcomePersists below.
        "innbucks.scan.event-day-check.enabled=false")
class TicketScanServiceAuditIT extends PostgresIntegrationTestBase {

    @Autowired private TicketScanService scanService;
    @Autowired private ScanAttemptRepository scanAttemptRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingItemRepository bookingItemRepository;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // Clean slate. CASCADE removes booking_items via the FK and is safe to
        // re-run between tests. scan_attempts has no FK to bookings.
        tx.executeWithoutResult(s -> {
            scanAttemptRepository.deleteAllInBatch();
            bookingItemRepository.deleteAllInBatch();
            bookingRepository.deleteAllInBatch();
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyOutcomeWritesExactlyOneScanAttemptsRow() {
        UUID organizerUuid = UUID.randomUUID();
        UUID otherOrganizer = UUID.randomUUID();

        // (1) CONFIRMED booking the organizer owns — drives ALLOWED then
        //     ALREADY_REDEEMED on a second scan.
        BookingItem confirmedItem = seedConfirmedItem(organizerUuid, "TKT-CONFIRMED-001");
        // (2) PENDING booking — drives BOOKING_NOT_CONFIRMED.
        BookingItem pendingItem = seedItem(organizerUuid, Booking.BookingStatus.PENDING, "TKT-PENDING-002");
        // (3) Booking the OTHER organizer owns — drives WRONG_ORGANIZER when
        //     our organizer scans it.
        BookingItem wrongOrgItem = seedConfirmedItem(otherOrganizer, "TKT-WRONGORG-003");
        // (4) A separate CONFIRMED booking for the assignment-check case.
        BookingItem teamMemberItem = seedConfirmedItem(organizerUuid, "TKT-TEAM-004");

        // ALLOWED — organizer scans their own confirmed ticket.
        authenticateAsOrganizer("organizer@example.com", organizerUuid);
        scanService.scan("TKT-CONFIRMED-001", "Rumbi Sibanda");

        // ALREADY_REDEEMED — same ticket again, still as the organizer.
        scanService.scan("TKT-CONFIRMED-001", "Rumbi Sibanda");

        // BOOKING_NOT_CONFIRMED — scan a PENDING booking's ticket.
        scanService.scan("TKT-PENDING-002", "Rumbi Sibanda");

        // WRONG_ORGANIZER — our organizer scans someone else's ticket.
        scanService.scan("TKT-WRONGORG-003", "Rumbi Sibanda");

        // TICKET_NOT_FOUND — bogus number.
        scanService.scan("BOGUS-999", "Rumbi Sibanda");

        // NOT_ASSIGNED_TO_EVENT — TEAM_MEMBER, fail-closed default, user-service
        // fallback yields "service down" → assignment check applies failOpen=false
        // (the production default) → NOT_ASSIGNED.
        authenticateAsTeamMember("tariro@harare-arena.co.zw", organizerUuid);
        scanService.scan("TKT-TEAM-004", "Tariro Chikomo");

        List<ScanAttempt> rows = scanAttemptRepository.findAll();
        assertThat(rows).as("one row per scan, six scans pumped").hasSize(6);

        Map<ScanAttempt.Outcome, Long> byOutcome = rows.stream()
                .collect(Collectors.groupingBy(ScanAttempt::getOutcome, Collectors.counting()));
        assertThat(byOutcome).containsOnly(
                Map.entry(ScanAttempt.Outcome.ALLOWED, 1L),
                Map.entry(ScanAttempt.Outcome.ALREADY_REDEEMED, 1L),
                Map.entry(ScanAttempt.Outcome.BOOKING_NOT_CONFIRMED, 1L),
                Map.entry(ScanAttempt.Outcome.WRONG_ORGANIZER, 1L),
                Map.entry(ScanAttempt.Outcome.TICKET_NOT_FOUND, 1L),
                Map.entry(ScanAttempt.Outcome.NOT_ASSIGNED_TO_EVENT, 1L));

        // Bonus: the ALLOWED row should carry the booking/event linkage; the
        // not-found row should not (no item resolved).
        ScanAttempt allowed = rows.stream()
                .filter(r -> r.getOutcome() == ScanAttempt.Outcome.ALLOWED)
                .findFirst().orElseThrow();
        assertThat(allowed.getBookingItemId()).isEqualTo(confirmedItem.getId());
        assertThat(allowed.getEventId()).isEqualTo(confirmedItem.getBooking().getEventId());

        ScanAttempt notFound = rows.stream()
                .filter(r -> r.getOutcome() == ScanAttempt.Outcome.TICKET_NOT_FOUND)
                .findFirst().orElseThrow();
        assertThat(notFound.getBookingItemId()).isNull();
        assertThat(notFound.getEventId()).isNull();
        assertThat(notFound.getTicketNumber()).isEqualTo("BOGUS-999");
    }

    // -----------------------------------------------------------------
    // Seed helpers — write rows directly via JPA so the test owns the
    // shape (status, tenantUserUuid, ticketNumber) without going through
    // BookingService's create-flow Feign calls.
    // -----------------------------------------------------------------

    private BookingItem seedConfirmedItem(UUID organizerUuid, String ticketNumber) {
        return seedItem(organizerUuid, Booking.BookingStatus.CONFIRMED, ticketNumber);
    }

    private BookingItem seedItem(UUID organizerUuid, Booking.BookingStatus status, String ticketNumber) {
        return tx.execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail("buyer@example.com")
                    .eventId(UUID.randomUUID())
                    .confirmationNumber("INN-IT-" + UUID.randomUUID())
                    .status(status)
                    .totalAmount(new BigDecimal("100.00"))
                    .tenantUserUuid(organizerUuid)
                    .build();
            bookingRepository.save(booking);
            BookingItem item = BookingItem.builder()
                    .booking(booking)
                    .seatId(UUID.randomUUID())
                    .categoryId(UUID.randomUUID())
                    .rowLabel("A")
                    .seatNumber(1)
                    .categoryName("General")
                    .priceAtBooking(new BigDecimal("100.00"))
                    .ticketNumber(ticketNumber)
                    .isActive(true)
                    .build();
            bookingItemRepository.save(item);
            return item;
        });
    }

    private void authenticateAsOrganizer(String email, UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        // For an organizer scan we set userUuid == organizerUuid (the convention
        // user-service follows when minting an EVENT_ORGANIZER token).
        auth.setDetails(new JwtAuthDetails(email, null, organizerUuid, organizerUuid,
                "Rumbi", "Sibanda"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsTeamMember(String email, UUID organizerUuid) {
        var auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_TEAM_MEMBER")));
        auth.setDetails(new JwtAuthDetails(email, null, UUID.randomUUID(), organizerUuid,
                "Tariro", "Chikomo"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * V23 widened {@code scan_attempts}' CHECK constraint to admit
     * WRONG_EVENT_DAY. Only a real Postgres can prove that: the column is TEXT
     * guarded by {@code chk_outcome}, so without the migration the very first
     * off-day scan in production would fail the INSERT at commit and roll the
     * whole scan transaction back — turning an intended refusal into a 500.
     * A mock-based test cannot see this at all.
     */
    @Test
    void wrongEventDayOutcomePersists_soTheV23CheckConstraintAdmitsIt() {
        ScanAttempt attempt = ScanAttempt.builder()
                // ScanAttempt implements Persistable<UUID> with a manually
                // assigned id — the same thing recordAttempt does.
                .id(UUID.randomUUID())
                .ticketNumber("20260619-48291X")
                .outcome(ScanAttempt.Outcome.WRONG_EVENT_DAY)
                .attemptedAt(java.time.Instant.now())
                .scannerDisplayName("Tariro Chikomo")
                .build();

        tx.executeWithoutResult(st -> scanAttemptRepository.saveAndFlush(attempt));

        List<ScanAttempt> rows = scanAttemptRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOutcome()).isEqualTo(ScanAttempt.Outcome.WRONG_EVENT_DAY);
    }

    @SuppressWarnings("unused")
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
