package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.client.ScannerInviteGateway;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSalesAssignment;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scanner schedule rule against real Postgres (V14): the overlap SQL, the
 * constraints, and two organizers assigning the same number at once. Since
 * V21 also the sales-agent columns and table, the sellers query and the
 * seller check booking-service makes.
 *
 * <p>Deliberately not {@code @Transactional}: the race needs two real
 * transactions, so every test cleans up after the tables it writes.
 */
class EventScannerPostgresIT extends PostgresIntegrationTestBase {

    private static final String PHONE = "+263771234567";

    @Autowired EventRepository eventRepository;
    @Autowired EventScannerRepository scannerRepository;
    @Autowired EventScannerAssignmentRepository assignmentRepository;
    @Autowired EventTermsVersionRepository termsVersionRepository;
    @Autowired EventBookingQuestionRepository bookingQuestionRepository;
    @Autowired ScannerService scannerService;
    @Autowired ScannerInviteGateway realInviteGateway;

    /** Records the invite messages instead of calling user-service (V20); restored after each test. */
    private ScannerInviteGateway inviteGateway;

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    private final UUID organizerA = UUID.randomUUID();
    private final UUID organizerB = UUID.randomUUID();

    // Before AND after: the Postgres container is shared by every IT in the
    // module, and EventRepositoryPostgresIT counts every active event, so rows
    // left behind here change its answer.
    @BeforeEach
    @AfterEach
    void clean() {
        // V21: selling assignments reference events too.
        salesAssignmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        scannerRepository.deleteAll();
        // V16's tables reference events: clear them first, or another IT's
        // terms or questions make this delete fail.
        termsVersionRepository.deleteAll();
        bookingQuestionRepository.deleteAll();
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

    @BeforeEach
    void recordInviteMessages() {
        inviteGateway = mock(ScannerInviteGateway.class);
        scannerService.setScannerInviteGateway(inviteGateway);
    }

    // The bean is shared by every IT in the context: put the real one back.
    @AfterEach
    void restoreInviteGateway() {
        scannerService.setScannerInviteGateway(realInviteGateway);
    }

    // ---------------------------------------------------------- invite message (V20)

    @Test
    void twoFirstAssignmentsAtOnce_sendOneMessage_afterTheCommit() throws Exception {
        Event first = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));
        Event second = event(organizerA, now.plusDays(2).withHour(10), now.plusDays(2).withHour(12));
        List<Boolean> sentInsideATransaction = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            sentInsideATransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(inviteGateway).sendInvite(any(), any(), any(), any(), any());

        for (int round = 0; round < 10; round++) {
            assignmentRepository.deleteAll();
            scannerRepository.deleteAll();
            clearInvocations(inviteGateway);
            EventScanner s = scanner(organizerA, PHONE);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<String>> results = new ArrayList<>();
                results.add(pool.submit(() -> attempt(start, organizerA, s, first)));
                results.add(pool.submit(() -> attempt(start, organizerA, s, second)));
                start.countDown();
                for (Future<String> f : results) {
                    assertThat(f.get(30, TimeUnit.SECONDS)).as("round " + round).isEqualTo("assigned");
                }
            } finally {
                pool.shutdownNow();
            }
            verify(inviteGateway, times(1)).sendInvite(eq(s.getScannerId()), eq(PHONE), any(), any(), any());
            assertThat(scannerRepository.findById(s.getScannerId()).orElseThrow().getInviteNotifiedAt())
                    .as("round " + round).isNotNull();
        }
        assertThat(sentInsideATransaction).hasSize(10).containsOnly(false);
    }

    @Test
    void laterAssignments_sendNothing_butInvitingADeclinerAgainSendsOnceMore() {
        EventScanner s = scanner(organizerA, PHONE);
        Event jazz = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));
        Event ball = event(organizerA, now.plusDays(2).withHour(10), now.plusDays(2).withHour(12));

        scannerService.assign(organizerA, "it", s.getScannerId(), jazz.getEventId());
        scannerService.assign(organizerA, "it", s.getScannerId(), ball.getEventId());
        verify(inviteGateway, times(1)).sendInvite(s.getScannerId(), PHONE, null, jazz.getTitle(), List.of("SCAN"));

        scannerService.decline(null, PHONE, s.getScannerId());
        scannerService.reinvite(organizerA, "it", s.getScannerId());

        verify(inviteGateway, times(2)).sendInvite(s.getScannerId(), PHONE, null, jazz.getTitle(), List.of("SCAN"));
        assertThat(scannerRepository.findById(s.getScannerId()).orElseThrow().getInviteNotifiedAt()).isNotNull();
    }

    @Test
    void anAcceptedScanner_isNeverMessaged() {
        EventScanner s = scanner(organizerA, PHONE);
        s.setAcceptedAt(now);
        s.setAccountUserUuid(UUID.randomUUID());
        scannerRepository.saveAndFlush(s);
        Event jazz = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));

        scannerService.assign(organizerA, "it", s.getScannerId(), jazz.getEventId());

        verifyNoInteractions(inviteGateway);
        assertThat(scannerRepository.findById(s.getScannerId()).orElseThrow().getInviteNotifiedAt()).isNull();
    }

    // ------------------------------------------------------------- sales agents (V21)

    @Autowired EventSalesAssignmentRepository salesAssignmentRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(st -> work.get());
    }

    private EventScanner agent(UUID organizer, String phone) {
        return scannerRepository.saveAndFlush(EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizer)
                .phoneNumber(phone)
                .firstName("Tendai")
                .lastName("Moyo")
                .canScan(false)
                .canSell(true)
                .build());
    }

    private EventScanner acceptedBy(EventScanner s, UUID account) {
        s.setAcceptedAt(now);
        s.setAccountUserUuid(account);
        return scannerRepository.saveAndFlush(s);
    }

    private EventSalesAssignment sell(EventScanner s, Event e, Integer cap, String commission) {
        return salesAssignmentRepository.saveAndFlush(EventSalesAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .scannerId(s.getScannerId())
                .eventId(e.getEventId())
                .ticketCap(cap)
                .commissionPercent(new java.math.BigDecimal(commission))
                .build());
    }

    @Test
    void v21_aRowWrittenWithoutTheNewColumns_isAScanOnlyMember_withNoPortalAccess() {
        // What every row that predates V21 looks like: the old INSERT names none of the new columns.
        UUID id = UUID.randomUUID();
        inTransaction(() -> entityManager.createNativeQuery("""
                        INSERT INTO event_scanners (scanner_id, organizer_uuid, phone_number, first_name, last_name,
                                                    created_at, updated_at, version)
                        VALUES (?1, ?2, ?3, 'Tendai', 'Moyo', now(), now(), 0)""")
                .setParameter(1, id).setParameter(2, organizerA).setParameter(3, PHONE)
                .executeUpdate());

        EventScanner old = scannerRepository.findById(id).orElseThrow();
        assertThat(old.isCanScan()).isTrue();
        assertThat(old.isCanSell()).isFalse();
        assertThat(old.getPortalEmail()).isNull();
        assertThat(old.getPortalInvitedAt()).isNull();
    }

    @Test
    void v21_aRowWithNoRole_isRefusedByTheDatabase() {
        EventScanner s = EventScanner.builder()
                .scannerId(UUID.randomUUID()).organizerUuid(organizerA).phoneNumber(PHONE)
                .firstName("Tendai").lastName("Moyo").canScan(false).canSell(false).build();
        assertThatThrownBy(() -> scannerRepository.saveAndFlush(s))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v21_salesAssignments_areOnePerEvent_capAtLeastOne_commission0To100_andGoWithTheirRow() {
        EventScanner s = agent(organizerA, PHONE);
        Event e = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));
        EventSalesAssignment terms = sell(s, e, 200, "5.00");
        assertThat(salesAssignmentRepository.findById(terms.getAssignmentId()).orElseThrow().getCommissionPercent())
                .isEqualByComparingTo("5.00");

        assertThatThrownBy(() -> sell(s, e, null, "0")).as("one per (row, event)")
                .isInstanceOf(DataIntegrityViolationException.class);
        Event other = event(organizerA, now.plusDays(2).withHour(10), now.plusDays(2).withHour(12));
        assertThatThrownBy(() -> sell(s, other, 0, "0")).as("cap 0")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sell(s, other, null, "100.01")).as("over 100%")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sell(s, other, null, "-1")).as("negative")
                .isInstanceOf(DataIntegrityViolationException.class);

        // The row goes, its selling assignments go with it.
        scannerRepository.deleteById(s.getScannerId());
        scannerRepository.flush();
        assertThat(salesAssignmentRepository.count()).isZero();
    }

    @Test
    void v21_anotherOrganizersSellOnlyRow_doesNotCountForTheScanningSchedule() {
        EventScanner atA = scanner(organizerA, PHONE);
        EventScanner sellerAtB = agent(organizerB, PHONE);
        Event bEvent = event(organizerB, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23));
        // A scan assignment B made while the row still scanned, kept after Scan was taken away.
        assign(sellerAtB, bEvent);
        Event overlapping = event(organizerA, now.plusDays(1).withHour(20), now.plusDays(1).withHour(22));

        assertThat(clashes(atA, overlapping)).as("a sell-only row's kept scan events block nothing").isZero();

        sellerAtB.setCanScan(true);
        scannerRepository.saveAndFlush(sellerAtB);
        assertThat(clashes(atA, overlapping)).as("once it scans again, they count").isEqualTo(1);
    }

    @Test
    void v21_givingScanBack_whenItsEventNowOverlaps_isRefused() {
        EventScanner sellerAtA = agent(organizerA, PHONE);
        assign(sellerAtA, event(organizerA, now.plusDays(1).withHour(18), now.plusDays(1).withHour(23)));
        EventScanner atB = scanner(organizerB, PHONE);
        assign(atB, event(organizerB, now.plusDays(1).withHour(20), now.plusDays(1).withHour(22)));

        assertThatThrownBy(() -> scannerService.setRoles(organizerA, "it", sellerAtA.getScannerId(), true, true))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ScannerService.SCHEDULE_CLASH));
        assertThat(scannerRepository.findById(sellerAtA.getScannerId()).orElseThrow().isCanScan()).isFalse();
    }

    @Test
    void v21_theSellersQuery_returnsOnlyThisAccountsAcceptedSwitchedOnSellingRows() {
        UUID account = UUID.randomUUID();
        EventScanner selling = acceptedBy(agent(organizerA, PHONE), account);
        acceptedBy(scanner(organizerB, PHONE), account);                      // scans only
        EventScanner off = acceptedBy(agent(UUID.randomUUID(), PHONE), account);
        off.setDisabledAt(now);
        scannerRepository.saveAndFlush(off);
        agent(UUID.randomUUID(), PHONE);                                      // invited, not accepted
        acceptedBy(agent(UUID.randomUUID(), "+263779999999"), UUID.randomUUID()); // someone else

        assertThat(scannerRepository.findActiveSellersForAccount(account))
                .extracting(EventScanner::getScannerId).containsExactly(selling.getScannerId());
    }

    @Test
    void v21_sellerAccess_againstPostgres_isLive_onEveryRemoval() {
        UUID account = UUID.randomUUID();
        EventScanner s = acceptedBy(agent(organizerA, PHONE), account);
        Event e = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));

        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("not assigned yet").isFalse();

        scannerService.assignSalesEvent(organizerA, "it", s.getScannerId(), e.getEventId(), 200,
                new java.math.BigDecimal("5"));
        var access = scannerService.sellerAccess(account, e.getEventId());
        assertThat(access.allowed()).isTrue();
        assertThat(access.agentId()).isEqualTo(s.getScannerId());
        assertThat(access.organizerUuid()).isEqualTo(organizerA);
        assertThat(access.ticketCap()).isEqualTo(200);
        assertThat(access.commissionPercent()).isEqualByComparingTo("5.00");
        assertThat(scannerService.sellerAccess(UUID.randomUUID(), e.getEventId()).allowed())
                .as("another account").isFalse();

        scannerService.setRoles(organizerA, "it", s.getScannerId(), true, false);
        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("Sell taken away").isFalse();
        scannerService.setRoles(organizerA, "it", s.getScannerId(), true, true);
        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("Sell back").isTrue();

        scannerService.disable(organizerA, "it", s.getScannerId());
        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("switched off").isFalse();
        scannerService.enable(organizerA, "it", s.getScannerId());

        scannerService.unassignSalesEvent(organizerA, "it", s.getScannerId(), e.getEventId());
        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("unassigned").isFalse();

        scannerService.assignSalesEvent(organizerA, "it", s.getScannerId(), e.getEventId(), null, null);
        e.setDeleted(true);
        eventRepository.saveAndFlush(e);
        assertThat(scannerService.sellerAccess(account, e.getEventId()).allowed()).as("event deleted").isFalse();
    }

    @Test
    void v21_assignSalesEvent_upserts_andTheFirstAssignmentMessagesOnce_asASalesAgent() {
        EventScanner s = agent(organizerA, PHONE);
        Event jazz = event(organizerA, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));

        scannerService.assignSalesEvent(organizerA, "it", s.getScannerId(), jazz.getEventId(), 200, null);
        scannerService.assignSalesEvent(organizerA, "it", s.getScannerId(), jazz.getEventId(), null,
                new java.math.BigDecimal("2.5"));

        assertThat(salesAssignmentRepository.findAll()).singleElement().satisfies(t -> {
            assertThat(t.getTicketCap()).isNull();
            assertThat(t.getCommissionPercent()).isEqualByComparingTo("2.50");
        });
        verify(inviteGateway, times(1)).sendInvite(s.getScannerId(), PHONE, null, jazz.getTitle(), List.of("SELL"));

        var listed = scannerService.get(organizerA, s.getScannerId());
        assertThat(listed.events()).isEmpty();
        assertThat(listed.salesEvents()).singleElement()
                .satisfies(v -> assertThat(v.eventId()).isEqualTo(jazz.getEventId()));
    }

    @Test
    void v21_theAgentsList_comesFromTheAccount_soonestFirst() {
        UUID account = UUID.randomUUID();
        EventScanner atA = acceptedBy(agent(organizerA, PHONE), account);
        EventScanner atB = acceptedBy(agent(organizerB, PHONE), account);
        Event later = event(organizerA, now.plusDays(3).withHour(10), now.plusDays(3).withHour(12));
        Event sooner = event(organizerB, now.plusDays(1).withHour(10), now.plusDays(1).withHour(12));
        Event ended = event(organizerA, now.minusDays(1).withHour(10), now.minusDays(1).withHour(12));
        sell(atA, later, 50, "5");
        sell(atB, sooner, null, "0");
        sell(atA, ended, null, "0");

        assertThat(scannerService.sales(account).events())
                .extracting(e -> e.eventId())
                .containsExactly(sooner.getEventId(), later.getEventId());
    }
}
