package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.service.ScannerService;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scanner schedule rule against real Postgres (V14): the overlap SQL, the
 * constraints, and two organizers assigning the same number at once.
 *
 * <p>Deliberately not {@code @Transactional}: the race needs two real
 * transactions, so every test cleans up after the tables it writes.
 */
class EventScannerPostgresIT extends PostgresIntegrationTestBase {

    private static final String PHONE = "+263771234567";

    @Autowired EventRepository eventRepository;
    @Autowired EventScannerRepository scannerRepository;
    @Autowired EventScannerAssignmentRepository assignmentRepository;
    @Autowired ScannerService scannerService;

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    private final UUID organizerA = UUID.randomUUID();
    private final UUID organizerB = UUID.randomUUID();

    // Before AND after: the Postgres container is shared by every IT in the
    // module, and EventRepositoryPostgresIT counts every active event, so rows
    // left behind here change its answer.
    @BeforeEach
    @AfterEach
    void clean() {
        assignmentRepository.deleteAll();
        scannerRepository.deleteAll();
        eventRepository.deleteAll();
    }

    private Event event(UUID organizer, LocalDateTime start, LocalDateTime end) {
        return eventRepository.save(Event.builder()
                .tenantUserUuid(organizer)
                .title("Event " + UUID.randomUUID())
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(start)
                .endDateTime(end)
                .totalCapacity(100)
                .availableTickets(100)
                .active(true)
                .deleted(false)
                .build());
    }

    private EventScanner scanner(UUID organizer, String phone) {
        return scannerRepository.saveAndFlush(EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizer)
                .phoneNumber(phone)
                .firstName("Tendai")
                .lastName("Moyo")
                .build());
    }

    private void assign(EventScanner scanner, Event event) {
        assignmentRepository.saveAndFlush(EventScannerAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .scannerId(scanner.getScannerId())
                .eventId(event.getEventId())
                .build());
    }

    private long clashes(EventScanner scanner, Event candidate) {
        return assignmentRepository.countClashes(PHONE, scanner.getScannerId(), candidate.getEventId(),
                candidate.getStartDateTime(), candidate.getEndDateTime(), now);
    }

    @Test
    void anOverlappingEventOfAnotherOrganizer_clashes() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        assign(atA, event(organizerA, now.plusDays(1).withHour(18), now.plusDays(2).withHour(1)));

        assertThat(clashes(atB, event(organizerB, now.plusDays(1).withHour(20), now.plusDays(1).withHour(23))))
                .isEqualTo(1);
    }

    @Test
    void backToBackEvents_doNotClash_theWindowIsHalfOpen() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        LocalDateTime day = now.plusDays(1).withHour(0);
        assign(atA, event(organizerA, day.withHour(12), day.withHour(18)));

        assertThat(clashes(atB, event(organizerB, day.withHour(18), day.withHour(22)))).isZero();
    }

    @Test
    void twoOverlappingEventsOfTheSameOrganizer_clash() {
        // Owner decision: organizers may run overlapping events; one scanner may not be at both.
        EventScanner s = scanner(organizerA, PHONE);
        assign(s, event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23)));

        assertThat(clashes(s, event(organizerA, now.plusDays(1).withHour(21), now.plusDays(2).withHour(2))))
                .isEqualTo(1);
    }

    @Test
    void endedAndDeletedEvents_neverClash() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        assign(atA, event(organizerA, now.minusHours(6), now.minusHours(1)));
        Event deleted = event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23));
        deleted.setDeleted(true);
        eventRepository.saveAndFlush(deleted);
        assign(atA, deleted);

        // A live event that began before the ended one finished, and one over the deleted one.
        assertThat(clashes(atB, event(organizerB, now.minusHours(3), now.plusHours(2)))).isZero();
        assertThat(clashes(atB, event(organizerB, now.plusDays(1).withHour(19), now.plusDays(1).withHour(22))))
                .isZero();
    }

    @Test
    void anotherOrganizersSwitchedOffOrDeclinedRow_doesNotCount_butTheRowsOwnEventsAlwaysDo() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        Event aEvent = event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23));
        assign(atA, aEvent);
        Event overlapping = event(organizerB, now.plusDays(1).withHour(20), now.plusDays(1).withHour(22));

        // Each save returns the managed copy at its new @Version; keep it, or
        // the next save of the old copy is an optimistic-lock failure.
        atA.setDisabledAt(now);
        atA = scannerRepository.saveAndFlush(atA);
        assertThat(clashes(atB, overlapping)).as("A switched them off").isZero();

        atA.setDisabledAt(null);
        atA.setDeclinedAt(now);
        atA = scannerRepository.saveAndFlush(atA);
        assertThat(clashes(atB, overlapping)).as("they declined A").isZero();

        // A's own switched-off row still may not take an overlapping event of A's.
        atA.setDeclinedAt(null);
        atA.setDisabledAt(now);
        atA = scannerRepository.saveAndFlush(atA);
        Event aOverlapping = event(organizerA, now.plusDays(1).withHour(21), now.plusDays(1).withHour(23));
        assertThat(assignmentRepository.countClashes(PHONE, atA.getScannerId(), aOverlapping.getEventId(),
                aOverlapping.getStartDateTime(), aOverlapping.getEndDateTime(), now)).isEqualTo(1);
    }

    @Test
    void comingBack_withAnEventThatNowOverlaps_clashes() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        assign(atA, event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23)));
        atA.setDisabledAt(now);
        scannerRepository.saveAndFlush(atA);
        assign(atB, event(organizerB, now.plusDays(1).withHour(20), now.plusDays(1).withHour(22)));

        assertThat(assignmentRepository.countClashesOnReturn(PHONE, atA.getScannerId(), now)).isEqualTo(1);

        EventScanner atC = scanner(UUID.randomUUID(), PHONE);
        assertThat(assignmentRepository.countClashesOnReturn(PHONE, atC.getScannerId(), now))
                .as("a row with no events has nothing to clash").isZero();
    }

    @Test
    void oneNumberPerOrganizer_andAcceptingBindsAnAccount() {
        scanner(organizerA, PHONE);
        assertThatThrownBy(() -> scanner(organizerA, PHONE)).isInstanceOf(DataIntegrityViolationException.class);

        EventScanner s = scanner(organizerB, PHONE);
        s.setAcceptedAt(now);
        assertThatThrownBy(() -> scannerRepository.saveAndFlush(s))
                .as("accepted with no account")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theLockQuery_returnsEveryRowOfTheNumber() {
        scanner(organizerA, PHONE);
        scanner(organizerB, PHONE);
        scanner(organizerA, "+263779999999");

        List<EventScanner> locked = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(st -> scannerRepository.lockAllByPhoneNumber(PHONE));

        assertThat(locked).hasSize(2).allMatch(s -> s.getPhoneNumber().equals(PHONE));
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void twoOrganizersAssigningTheNumberToOverlappingEventsAtOnce_onlyOneWins() throws Exception {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner atB = scanner(organizerB, PHONE);
        Event aEvent = event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23));
        Event bEvent = event(organizerB, now.plusDays(1).withHour(20), now.plusDays(1).withHour(22));

        for (int round = 0; round < 10; round++) {
            assignmentRepository.deleteAll();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<String>> results = new ArrayList<>();
                results.add(pool.submit(() -> attempt(start, organizerA, atA, aEvent)));
                results.add(pool.submit(() -> attempt(start, organizerB, atB, bEvent)));
                start.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : results) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(outcomes).as("round " + round).containsExactlyInAnyOrder("assigned", "clash");
                assertThat(assignmentRepository.count()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private String attempt(CountDownLatch start, UUID organizer, EventScanner scanner, Event event)
            throws InterruptedException {
        start.await();
        try {
            scannerService.assign(organizer, "it", scanner.getScannerId(), event.getEventId());
            return "assigned";
        } catch (ApiProblemException e) {
            return ScannerService.SCHEDULE_CLASH.equals(e.getErrorCode()) ? "clash" : e.getErrorCode();
        }
    }
}
