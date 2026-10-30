package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.scanner.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScannerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 12, 0);
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID OTHER_ORGANIZER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String PHONE = "+263771234567";

    private EventScannerRepository scanners;
    private EventScannerAssignmentRepository assignments;
    private EventRepository events;
    private OrganizerGateway organizerGateway;
    private ScannerService service;

    @BeforeEach
    void setUp() {
        scanners = mock(EventScannerRepository.class);
        assignments = mock(EventScannerAssignmentRepository.class);
        events = mock(EventRepository.class);
        organizerGateway = mock(OrganizerGateway.class);
        service = new ScannerService(scanners, assignments, events, organizerGateway);
        service.setClock(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(organizerGateway.organizersByUserUuids(any())).thenReturn(Map.of());
        when(scanners.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scanners.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static EventScanner scanner(UUID organizer, String phone) {
        return EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizer)
                .phoneNumber(phone)
                .firstName("Tendai")
                .lastName("Moyo")
                .createdAt(NOW.minusDays(1))
                .build();
    }

    private static EventScanner accepted(EventScanner s, UUID account) {
        s.setAcceptedAt(NOW.minusHours(5));
        s.setAccountUserUuid(account);
        return s;
    }

    private static Event event(UUID organizer, LocalDateTime start, LocalDateTime end) {
        return Event.builder()
                .eventId(UUID.randomUUID())
                .tenantUserUuid(organizer)
                .title("Peterhouse Sixth Form Ball")
                .venue("Peterhouse, Marondera")
                .startDateTime(start)
                .endDateTime(end)
                .deleted(false)
                .build();
    }

    private static void assertProblem(Throwable thrown, HttpStatus status, String errorCode) {
        assertThat(thrown).isInstanceOf(ApiProblemException.class);
        ApiProblemException problem = (ApiProblemException) thrown;
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getErrorCode()).isEqualTo(errorCode);
    }

    // ------------------------------------------------------------------- add

    @Test
    void add_canonicalisesThePhone_andStartsInvited() {
        ScannerResponseDTO created = service.add(ORGANIZER, "org@example.com",
                new CreateScannerRequestDTO("Tendai", "Moyo", "0771234567"));

        assertThat(created.phoneNumber()).isEqualTo(PHONE);
        assertThat(created.status()).isEqualTo("INVITED");
        assertThat(created.events()).isEmpty();
        ArgumentCaptor<EventScanner> saved = ArgumentCaptor.forClass(EventScanner.class);
        verify(scanners).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAccountUserUuid()).as("no account is looked up or bound at add").isNull();
        assertThat(saved.getValue().getOrganizerUuid()).isEqualTo(ORGANIZER);
    }

    @Test
    void add_stripsMarkupFromTheNames() {
        ScannerResponseDTO created = service.add(ORGANIZER, "org@example.com",
                new CreateScannerRequestDTO("<b>Tendai</b>", "Moyo<script>x</script>", "0771234567"));
        assertThat(created.firstName()).isEqualTo("Tendai");
        assertThat(created.lastName()).isEqualTo("Moyo");
    }

    @Test
    void add_refusesANumberThatIsNotAMobileNumber() {
        assertProblem(catchThrowable(() -> service.add(ORGANIZER, "org", new CreateScannerRequestDTO("A", "B", "12"))),
                HttpStatus.BAD_REQUEST, ScannerService.INVALID_PHONE);
        verify(scanners, never()).saveAndFlush(any());
    }

    @Test
    void add_aNumberAlreadyOnTheTeam_namesTheExistingScanner() {
        EventScanner existing = scanner(ORGANIZER, PHONE);
        when(scanners.findByOrganizerUuidAndPhoneNumber(ORGANIZER, PHONE)).thenReturn(Optional.of(existing));

        Throwable thrown = catchThrowable(() -> service.add(ORGANIZER, "org",
                new CreateScannerRequestDTO("Tendai", "Moyo", "+263 77 123 4567")));

        assertProblem(thrown, HttpStatus.CONFLICT, ScannerService.ALREADY_ON_TEAM);
        assertThat(((ApiProblemException) thrown).getExtra()).containsEntry("scannerId", existing.getScannerId());
    }

    @Test
    void add_theSameNumberOnAnotherOrganizersTeam_isAllowed() {
        when(scanners.findByOrganizerUuidAndPhoneNumber(OTHER_ORGANIZER, PHONE))
                .thenReturn(Optional.of(scanner(OTHER_ORGANIZER, PHONE)));

        assertThat(service.add(ORGANIZER, "org", new CreateScannerRequestDTO("Tendai", "Moyo", PHONE)).status())
                .isEqualTo("INVITED");
    }

    @Test
    void add_pastTheLimit_isRefused() {
        service.setMaxPerOrganizer(2);
        when(scanners.countByOrganizerUuid(ORGANIZER)).thenReturn(2L);

        assertProblem(catchThrowable(() -> service.add(ORGANIZER, "org",
                new CreateScannerRequestDTO("Tendai", "Moyo", PHONE))), HttpStatus.CONFLICT, ScannerService.LIMIT_REACHED);
    }

    // ---------------------------------------------------------------- assign

    @Test
    void assign_anotherOrganizersEvent_isNotFound() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        Event theirs = event(OTHER_ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(4));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(events.findById(theirs.getEventId())).thenReturn(Optional.of(theirs));

        assertProblem(catchThrowable(() -> service.assign(ORGANIZER, "org", s.getScannerId(), theirs.getEventId())),
                HttpStatus.NOT_FOUND, ScannerService.EVENT_NOT_FOUND);
        verify(assignments, never()).save(any());
    }

    @Test
    void assign_anEndedEvent_isRefused() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        Event ended = event(ORGANIZER, NOW.minusHours(6), NOW.minusHours(1));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(events.findById(ended.getEventId())).thenReturn(Optional.of(ended));

        assertProblem(catchThrowable(() -> service.assign(ORGANIZER, "org", s.getScannerId(), ended.getEventId())),
                HttpStatus.CONFLICT, ScannerService.EVENT_ENDED);
    }

    @Test
    void assign_locksEveryRowOfTheNumber_beforeCountingClashes() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        Event e = event(ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(4));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));

        service.assign(ORGANIZER, "org", s.getScannerId(), e.getEventId());

        InOrder order = inOrder(scanners, assignments);
        order.verify(scanners).lockAllByPhoneNumber(PHONE);
        order.verify(assignments).countClashes(PHONE, s.getScannerId(), e.getEventId(),
                e.getStartDateTime(), e.getEndDateTime(), NOW);
        order.verify(assignments).save(any(EventScannerAssignment.class));
    }

    @Test
    void assign_overlappingAnotherAssignment_isRefused_andNamesNothing() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        Event e = event(ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(4));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
        when(assignments.countClashes(eq(PHONE), any(), any(), any(), any(), any())).thenReturn(1L);

        Throwable thrown = catchThrowable(() -> service.assign(ORGANIZER, "org", s.getScannerId(), e.getEventId()));

        assertProblem(thrown, HttpStatus.CONFLICT, ScannerService.SCHEDULE_CLASH);
        assertThat(((ApiProblemException) thrown).getExtra()).as("nothing about the other event").isEmpty();
        assertThat(thrown.getMessage()).isEqualTo("This number is already assigned to another event at that time.");
        verify(assignments, never()).save(any());
    }

    @Test
    void assign_twice_isIdempotent_andChecksAfterTheLock() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        Event e = event(ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(4));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
        when(assignments.existsByScannerIdAndEventId(s.getScannerId(), e.getEventId())).thenReturn(true);

        service.assign(ORGANIZER, "org", s.getScannerId(), e.getEventId());

        InOrder order = inOrder(scanners, assignments);
        order.verify(scanners).lockAllByPhoneNumber(PHONE);
        order.verify(assignments).existsByScannerIdAndEventId(s.getScannerId(), e.getEventId());
        verify(assignments, never()).countClashes(any(), any(), any(), any(), any(), any());
        verify(assignments, never()).save(any());
    }

    @Test
    void assign_someoneElsesScanner_isNotFound() {
        UUID theirs = UUID.randomUUID();
        when(scanners.findByScannerIdAndOrganizerUuid(theirs, ORGANIZER)).thenReturn(Optional.empty());
        assertProblem(catchThrowable(() -> service.assign(ORGANIZER, "org", theirs, UUID.randomUUID())),
                HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);
    }

    // -------------------------------------------------- enable and reinvite

    @Test
    void enable_whenAnAssignmentNowOverlaps_isRefused_andStaysOff() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        s.setDisabledAt(NOW.minusDays(1));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        when(assignments.countClashesOnReturn(PHONE, s.getScannerId(), NOW)).thenReturn(1L);

        assertProblem(catchThrowable(() -> service.enable(ORGANIZER, "org", s.getScannerId())),
                HttpStatus.CONFLICT, ScannerService.SCHEDULE_CLASH);
        assertThat(s.getDisabledAt()).isNotNull();
        verify(scanners).lockAllByPhoneNumber(PHONE);
    }

    @Test
    void enable_withNoOverlap_switchesBackOn() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        s.setDisabledAt(NOW.minusDays(1));
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));

        assertThat(service.enable(ORGANIZER, "org", s.getScannerId()).status()).isEqualTo("INVITED");
    }

    @Test
    void reinvite_onlyADeclinedScanner() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER)).thenReturn(Optional.of(s));
        assertProblem(catchThrowable(() -> service.reinvite(ORGANIZER, "org", s.getScannerId())),
                HttpStatus.CONFLICT, ScannerService.NOT_DECLINED);

        s.setDeclinedAt(NOW.minusDays(1));
        assertThat(service.reinvite(ORGANIZER, "org", s.getScannerId()).status()).isEqualTo("INVITED");
        verify(assignments).countClashesOnReturn(PHONE, s.getScannerId(), NOW);
    }

    // ------------------------------------------------------- accept/decline

    @Test
    void accept_bindsTheAccountThatAccepted() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        GateInviteDecisionDTO decision = service.accept(ACCOUNT, PHONE, s.getScannerId());

        assertThat(decision.status()).isEqualTo("ACTIVE");
        assertThat(s.getAccountUserUuid()).isEqualTo(ACCOUNT);
        assertThat(s.getAcceptedAt()).isEqualTo(NOW);
    }

    @Test
    void accept_anotherNumbersInvite_isNotFound() {
        EventScanner s = scanner(ORGANIZER, "+263779999999");
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertProblem(catchThrowable(() -> service.accept(ACCOUNT, PHONE, s.getScannerId())),
                HttpStatus.NOT_FOUND, ScannerService.INVITE_NOT_FOUND);
        assertThat(s.getAccountUserUuid()).isNull();
    }

    @Test
    void accept_aSwitchedOffInvite_isNotFound() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        s.setDisabledAt(NOW.minusHours(1));
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertProblem(catchThrowable(() -> service.accept(ACCOUNT, PHONE, s.getScannerId())),
                HttpStatus.NOT_FOUND, ScannerService.INVITE_NOT_FOUND);
    }

    @Test
    void accept_afterDeclining_needsTheOrganizerToInviteAgain() {
        EventScanner s = scanner(ORGANIZER, PHONE);
        s.setDeclinedAt(NOW.minusHours(1));
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertProblem(catchThrowable(() -> service.accept(ACCOUNT, PHONE, s.getScannerId())),
                HttpStatus.CONFLICT, ScannerService.INVITE_NOT_PENDING);
    }

    @Test
    void accept_twice_isIdempotent_butNotForAnotherAccountOnTheNumber() {
        EventScanner s = accepted(scanner(ORGANIZER, PHONE), ACCOUNT);
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertThat(service.accept(ACCOUNT, PHONE, s.getScannerId()).status()).isEqualTo("ACTIVE");
        // A recycled number: the new owner inherits nothing.
        assertProblem(catchThrowable(() -> service.accept(UUID.randomUUID(), PHONE, s.getScannerId())),
                HttpStatus.CONFLICT, ScannerService.INVITE_NOT_PENDING);
        assertThat(s.getAccountUserUuid()).isEqualTo(ACCOUNT);
    }

    @Test
    void decline_leavesAnAcceptedTeam_andUnbindsTheAccount() {
        EventScanner s = accepted(scanner(ORGANIZER, PHONE), ACCOUNT);
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertThat(service.decline(ACCOUNT, PHONE, s.getScannerId()).status()).isEqualTo("DECLINED");
        assertThat(s.getAccountUserUuid()).isNull();
        assertThat(s.getAcceptedAt()).isNull();
        assertThat(s.getDeclinedAt()).isEqualTo(NOW);

        // Idempotent.
        assertThat(service.decline(ACCOUNT, PHONE, s.getScannerId()).status()).isEqualTo("DECLINED");
    }

    @Test
    void decline_anotherAccountsTeam_onTheSameNumber_isNotFound() {
        EventScanner s = accepted(scanner(ORGANIZER, PHONE), ACCOUNT);
        when(scanners.findById(s.getScannerId())).thenReturn(Optional.of(s));

        assertProblem(catchThrowable(() -> service.decline(UUID.randomUUID(), PHONE, s.getScannerId())),
                HttpStatus.NOT_FOUND, ScannerService.INVITE_NOT_FOUND);
        assertThat(s.getAccountUserUuid()).isEqualTo(ACCOUNT);
    }

    @Test
    void theSuperAppEndpoints_needAPhoneOnTheSession() {
        assertProblem(catchThrowable(() -> service.gate(ACCOUNT, null)), HttpStatus.BAD_REQUEST,
                ScannerService.PHONE_REQUIRED);
        assertProblem(catchThrowable(() -> service.accept(ACCOUNT, " ", UUID.randomUUID())), HttpStatus.BAD_REQUEST,
                ScannerService.PHONE_REQUIRED);
        verifyNoInteractions(scanners);
    }

    // ------------------------------------------------------------------ gate

    @Test
    void gate_forSomeoneOnNoTeam_isEmpty_andAsksNoOrganizerNames() {
        GateResponseDTO gate = service.gate(ACCOUNT, PHONE);
        assertThat(gate.events()).isEmpty();
        assertThat(gate.invites()).isEmpty();
        verifyNoInteractions(organizerGateway);
    }

    @Test
    void gate_listsUpcomingAndLiveEvents_soonestFirst_andDropsEndedDeletedAndForeignOnes() {
        EventScanner s = accepted(scanner(ORGANIZER, PHONE), ACCOUNT);
        Event live = event(ORGANIZER, NOW.minusHours(1), NOW.plusHours(3));
        Event later = event(ORGANIZER, NOW.plusDays(2), NOW.plusDays(2).plusHours(4));
        Event ended = event(ORGANIZER, NOW.minusDays(1), NOW.minusDays(1).plusHours(3));
        Event deleted = event(ORGANIZER, NOW.plusDays(3), NOW.plusDays(3).plusHours(2));
        deleted.setDeleted(true);
        Event foreign = event(OTHER_ORGANIZER, NOW.plusDays(4), NOW.plusDays(4).plusHours(2));
        when(scanners.findActiveForAccount(ACCOUNT)).thenReturn(List.of(s));
        when(assignments.findByScannerIdIn(any())).thenReturn(List.of(
                row(s, later), row(s, live), row(s, ended), row(s, deleted), row(s, foreign)));
        when(events.findAllById(any())).thenReturn(List.of(live, later, ended, deleted, foreign));
        OrganizerDTO organizer = new OrganizerDTO();
        organizer.setBusinessName("Peterhouse Group of Schools");
        when(organizerGateway.organizersByUserUuids(any())).thenReturn(Map.of(ORGANIZER, organizer));

        GateResponseDTO gate = service.gate(ACCOUNT, PHONE);

        assertThat(gate.events()).extracting(GateEventDTO::eventId)
                .containsExactly(live.getEventId(), later.getEventId());
        assertThat(gate.events()).extracting(GateEventDTO::status).containsExactly("LIVE", "UPCOMING");
        assertThat(gate.events().get(0).organizerName()).isEqualTo("Peterhouse Group of Schools");
        assertThat(gate.invites()).isEmpty();
    }

    @Test
    void gate_listsPendingInvitesForTheSessionsNumber() {
        EventScanner invite = scanner(OTHER_ORGANIZER, PHONE);
        Event e = event(OTHER_ORGANIZER, NOW.plusDays(7), NOW.plusDays(7).plusHours(6));
        when(scanners.findPendingForPhone(PHONE)).thenReturn(List.of(invite));
        when(assignments.findByScannerIdIn(any())).thenReturn(List.of(row(invite, e)));
        when(events.findAllById(any())).thenReturn(List.of(e));

        GateResponseDTO gate = service.gate(ACCOUNT, "0771234567");

        assertThat(gate.events()).as("an invite is not a team until accepted").isEmpty();
        assertThat(gate.invites()).singleElement().satisfies(i -> {
            assertThat(i.scannerId()).isEqualTo(invite.getScannerId());
            assertThat(i.name()).isEqualTo("Tendai Moyo");
            assertThat(i.events()).extracting(ScannerEventDTO::eventId).containsExactly(e.getEventId());
        });
        verify(scanners).findPendingForPhone(PHONE);
    }

    // ---------------------------------------------------------------- access

    @Test
    void access_isAllowedOnlyForAnAcceptedScannerAssignedToTheEvent_onItsOrganizersTeam() {
        EventScanner s = accepted(scanner(ORGANIZER, PHONE), ACCOUNT);
        Event e = event(ORGANIZER, NOW, NOW.plusHours(4));
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
        when(scanners.findActiveForAccount(ACCOUNT)).thenReturn(List.of(s));

        assertThat(service.access(ACCOUNT, e.getEventId()).allowed()).as("not assigned").isFalse();

        when(assignments.existsByScannerIdAndEventId(s.getScannerId(), e.getEventId())).thenReturn(true);
        ScannerAccessDTO access = service.access(ACCOUNT, e.getEventId());
        assertThat(access.allowed()).isTrue();
        assertThat(access.organizerUuid()).isEqualTo(ORGANIZER);
        assertThat(access.displayName()).isEqualTo("Tendai Moyo");

        // An assignment row naming another organizer's event grants nothing.
        e.setTenantUserUuid(OTHER_ORGANIZER);
        assertThat(service.access(ACCOUNT, e.getEventId()).allowed()).isFalse();
    }

    @Test
    void access_forADeletedEvent_orNoAccount_isDenied() {
        Event e = event(ORGANIZER, NOW, NOW.plusHours(4));
        e.setDeleted(true);
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
        assertThat(service.access(ACCOUNT, e.getEventId()).allowed()).isFalse();
        assertThat(service.access(null, e.getEventId()).allowed()).isFalse();
        verify(scanners, never()).findActiveForAccount(any());
    }

    private static EventScannerAssignment row(EventScanner s, Event e) {
        return EventScannerAssignment.builder()
                .assignmentId(UUID.randomUUID()).scannerId(s.getScannerId()).eventId(e.getEventId())
                .assignedAt(NOW.minusDays(1)).build();
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }
}
