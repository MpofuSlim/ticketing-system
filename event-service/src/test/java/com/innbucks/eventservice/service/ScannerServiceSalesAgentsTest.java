package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.SalesAgentInviteGateway;
import com.innbucks.eventservice.client.ScannerInviteGateway;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.scanner.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSalesAssignment;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sales agents on the event team (V21): roles, selling assignments and their
 * terms, the portal invite, the invite message's roles, the agent's selling
 * list, and the seller check booking-service makes before every agent sale.
 */
class ScannerServiceSalesAgentsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 12, 0);
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID OTHER_ORGANIZER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "tendai.moyo@gmail.com";

    private EventScannerRepository scanners;
    private EventScannerAssignmentRepository assignments;
    private EventSalesAssignmentRepository sales;
    private EventRepository events;
    private OrganizerGateway organizerGateway;
    private ScannerInviteGateway inviteGateway;
    private SalesAgentInviteGateway portalGateway;
    private ScannerService service;

    @BeforeEach
    void setUp() {
        scanners = mock(EventScannerRepository.class);
        assignments = mock(EventScannerAssignmentRepository.class);
        sales = mock(EventSalesAssignmentRepository.class);
        events = mock(EventRepository.class);
        organizerGateway = mock(OrganizerGateway.class);
        service = new ScannerService(scanners, assignments, events, organizerGateway, sales);
        inviteGateway = mock(ScannerInviteGateway.class);
        portalGateway = mock(SalesAgentInviteGateway.class);
        service.setScannerInviteGateway(inviteGateway);
        service.setSalesAgentInviteGateway(portalGateway);
        service.setClock(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(organizerGateway.organizersByUserUuids(any())).thenReturn(Map.of());
        when(scanners.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scanners.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(sales.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ------------------------------------------------------------------ fixtures

    private static EventScanner member(UUID organizer, boolean canScan, boolean canSell) {
        return EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizer)
                .phoneNumber(PHONE)
                .firstName("Tendai")
                .lastName("Moyo")
                .canScan(canScan)
                .canSell(canSell)
                .createdAt(NOW.minusDays(1))
                .build();
    }

    private static EventScanner agent(UUID organizer) {
        return member(organizer, false, true);
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
                .title("Jazz on the Lawn")
                .venue("Harare Gardens")
                .startDateTime(start)
                .endDateTime(end)
                .deleted(false)
                .build();
    }

    private static Event upcoming(UUID organizer) {
        return event(organizer, NOW.plusDays(7), NOW.plusDays(7).plusHours(6));
    }

    private static EventSalesAssignment sale(EventScanner s, Event e, Integer cap, String commission) {
        return EventSalesAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .scannerId(s.getScannerId())
                .eventId(e.getEventId())
                .ticketCap(cap)
                .commissionPercent(new BigDecimal(commission))
                .assignedBy("org")
                .build();
    }

    private static EventScannerAssignment scanRow(EventScanner s, Event e) {
        return EventScannerAssignment.builder()
                .assignmentId(UUID.randomUUID()).scannerId(s.getScannerId()).eventId(e.getEventId()).build();
    }

    private void owns(EventScanner s) {
        when(scanners.findByScannerIdAndOrganizerUuid(s.getScannerId(), s.getOrganizerUuid()))
                .thenReturn(Optional.of(s));
        when(scanners.lockByScannerIdAndOrganizerUuid(s.getScannerId(), s.getOrganizerUuid()))
                .thenReturn(Optional.of(s));
    }

    private void exists(Event e) {
        when(events.findById(e.getEventId())).thenReturn(Optional.of(e));
    }

    private static void assertProblem(Throwable thrown, HttpStatus status, String errorCode) {
        assertThat(thrown).isInstanceOf(ApiProblemException.class);
        ApiProblemException problem = (ApiProblemException) thrown;
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getErrorCode()).isEqualTo(errorCode);
    }

    private static OrganizerDTO organizer(String businessName) {
        OrganizerDTO dto = new OrganizerDTO();
        dto.setBusinessName(businessName);
        return dto;
    }

    // --------------------------------------------------------------------- roles

    @Test
    void add_withoutRoles_isAScanOnlyMember_asBefore() {
        ScannerResponseDTO created = service.add(ORGANIZER, "org", new CreateScannerRequestDTO("Tendai", "Moyo", PHONE));

        assertThat(created.canScan()).isTrue();
        assertThat(created.canSell()).isFalse();
        assertThat(created.salesEvents()).isEmpty();
        assertThat(created.portalEmail()).isNull();
    }

    @Test
    void add_aSalesAgentOnly_storesTheRoles() {
        ScannerResponseDTO created = service.add(ORGANIZER, "org",
                new CreateScannerRequestDTO("Tendai", "Moyo", PHONE, false, true));

        assertThat(created.canScan()).isFalse();
        assertThat(created.canSell()).isTrue();
        ArgumentCaptor<EventScanner> saved = ArgumentCaptor.forClass(EventScanner.class);
        verify(scanners).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isCanScan()).isFalse();
        assertThat(saved.getValue().isCanSell()).isTrue();
    }

    @Test
    void add_withNoRole_isRefused_beforeAnything() {
        assertProblem(catchThrowable(() -> service.add(ORGANIZER, "org",
                        new CreateScannerRequestDTO("Tendai", "Moyo", PHONE, false, false))),
                HttpStatus.BAD_REQUEST, ScannerService.NO_ROLE);
        assertProblem(catchThrowable(() -> service.add(ORGANIZER, "org",
                        new CreateScannerRequestDTO("Tendai", "Moyo", PHONE, false, null))),
                HttpStatus.BAD_REQUEST, ScannerService.NO_ROLE);
        verifyNoInteractions(scanners);
    }

    @Test
    void setRoles_bothFalse_isRefused() {
        assertProblem(catchThrowable(() -> service.setRoles(ORGANIZER, "org", UUID.randomUUID(), false, false)),
                HttpStatus.BAD_REQUEST, ScannerService.NO_ROLE);
        verifyNoInteractions(scanners);
    }

    @Test
    void setRoles_anotherOrganizersMember_isNotFound() {
        EventScanner theirs = member(OTHER_ORGANIZER, true, false);
        owns(theirs);
        assertProblem(catchThrowable(() -> service.setRoles(ORGANIZER, "org", theirs.getScannerId(), true, true)),
                HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);
        assertThat(theirs.isCanSell()).isFalse();
    }

    @Test
    void setRoles_addingSell_needsNoScheduleCheck() {
        EventScanner s = member(ORGANIZER, true, false);
        owns(s);

        ScannerResponseDTO result = service.setRoles(ORGANIZER, "org", s.getScannerId(), true, true);

        assertThat(result.canSell()).isTrue();
        verify(scanners).save(s);
        verify(scanners, never()).lockAllByPhoneNumber(any());
    }

    @Test
    void setRoles_givingScanBackToACountingRow_isCheckedUnderTheNumbersLock() {
        EventScanner s = agent(ORGANIZER);
        owns(s);

        service.setRoles(ORGANIZER, "org", s.getScannerId(), true, true);

        InOrder order = inOrder(scanners, assignments);
        order.verify(scanners).lockAllByPhoneNumber(PHONE);
        order.verify(assignments).countClashesOnReturn(PHONE, s.getScannerId(), NOW);
        order.verify(scanners).save(s);
        assertThat(s.isCanScan()).isTrue();
    }

    @Test
    void setRoles_givingScanBack_whenAnEventNowOverlaps_isRefused_andNothingChanges() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        when(assignments.countClashesOnReturn(PHONE, s.getScannerId(), NOW)).thenReturn(1L);

        assertProblem(catchThrowable(() -> service.setRoles(ORGANIZER, "org", s.getScannerId(), true, true)),
                HttpStatus.CONFLICT, ScannerService.SCHEDULE_CLASH);
        assertThat(s.isCanScan()).isFalse();
        verify(scanners, never()).save(any());
    }

    @Test
    void setRoles_givingScanBackToASwitchedOffOrDeclinedRow_checksNothingYet() {
        // Such a row does not count: it is checked when it comes back.
        EventScanner off = agent(ORGANIZER);
        off.setDisabledAt(NOW.minusDays(1));
        EventScanner declined = agent(ORGANIZER);
        declined.setDeclinedAt(NOW.minusDays(1));
        owns(off);
        owns(declined);

        service.setRoles(ORGANIZER, "org", off.getScannerId(), true, true);
        service.setRoles(ORGANIZER, "org", declined.getScannerId(), true, false);

        verify(assignments, never()).countClashesOnReturn(any(), any(), any());
        assertThat(off.isCanScan()).isTrue();
        assertThat(declined.isCanSell()).isFalse();
    }

    @Test
    void setRoles_unchanged_writesNothing() {
        EventScanner s = member(ORGANIZER, true, true);
        owns(s);
        service.setRoles(ORGANIZER, "org", s.getScannerId(), true, true);
        verify(scanners, never()).save(any());
    }

    @Test
    void assign_toScan_aSellOnlyMember_isRefused_beforeTheLock() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);

        assertProblem(catchThrowable(() -> service.assign(ORGANIZER, "org", s.getScannerId(), e.getEventId())),
                HttpStatus.CONFLICT, ScannerService.NOT_A_SCANNER);
        verify(scanners, never()).lockAllByPhoneNumber(any());
        verify(assignments, never()).save(any());
    }

    @Test
    void enable_aSellOnlyRow_isNotScheduleChecked() {
        EventScanner s = agent(ORGANIZER);
        s.setDisabledAt(NOW.minusDays(1));
        owns(s);

        service.enable(ORGANIZER, "org", s.getScannerId());

        verify(scanners, never()).lockAllByPhoneNumber(any());
        assertThat(s.getDisabledAt()).isNull();
    }

    @Test
    void reinvite_aSellOnlyRow_isNotScheduleChecked() {
        EventScanner s = agent(ORGANIZER);
        s.setDeclinedAt(NOW.minusDays(1));
        owns(s);

        service.reinvite(ORGANIZER, "org", s.getScannerId());

        verify(assignments, never()).countClashesOnReturn(any(), any(), any());
        assertThat(s.getDeclinedAt()).isNull();
    }

    // ------------------------------------------------------- selling assignments

    @Test
    void assignSalesEvent_requiresTheSellRole() {
        EventScanner s = member(ORGANIZER, true, false);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);

        assertProblem(catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(),
                e.getEventId(), null, null)), HttpStatus.CONFLICT, ScannerService.NOT_A_SALES_AGENT);
        verify(sales, never()).save(any());
    }

    @Test
    void assignSalesEvent_someoneElsesMember_isNotFound() {
        UUID theirs = UUID.randomUUID();
        assertProblem(catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", theirs, UUID.randomUUID(),
                null, null)), HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);
    }

    @Test
    void assignSalesEvent_anotherOrganizersOrADeletedOrAnUnknownEvent_isNotFound() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event theirs = upcoming(OTHER_ORGANIZER);
        Event deleted = upcoming(ORGANIZER);
        deleted.setDeleted(true);
        exists(theirs);
        exists(deleted);

        for (UUID eventId : List.of(theirs.getEventId(), deleted.getEventId(), UUID.randomUUID())) {
            assertProblem(catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(),
                    eventId, null, null)), HttpStatus.NOT_FOUND, ScannerService.EVENT_NOT_FOUND);
        }
        verify(sales, never()).save(any());
    }

    @Test
    void assignSalesEvent_anEndedEvent_isRefused() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event ended = event(ORGANIZER, NOW.minusHours(6), NOW.minusHours(1));
        exists(ended);

        assertProblem(catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(),
                ended.getEventId(), 10, null)), HttpStatus.CONFLICT, ScannerService.EVENT_ENDED);
    }

    @Test
    void assignSalesEvent_storesTheTerms_atTwoDecimals_underTheRowLock_withNoClashRule() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), 200, new BigDecimal("5"));

        InOrder order = inOrder(scanners, sales);
        order.verify(scanners).lockByScannerIdAndOrganizerUuid(s.getScannerId(), ORGANIZER);
        order.verify(sales).findByScannerIdAndEventId(s.getScannerId(), e.getEventId());
        ArgumentCaptor<EventSalesAssignment> saved = ArgumentCaptor.forClass(EventSalesAssignment.class);
        order.verify(sales).save(saved.capture());
        assertThat(saved.getValue().getTicketCap()).isEqualTo(200);
        assertThat(saved.getValue().getCommissionPercent()).isEqualByComparingTo("5.00");
        assertThat(saved.getValue().getCommissionPercent().scale()).isEqualTo(2);
        assertThat(saved.getValue().getAssignedBy()).isEqualTo("org");
        // Selling has no overlap rule: the number is never locked or counted.
        verify(scanners, never()).lockAllByPhoneNumber(any());
        verify(assignments, never()).countClashes(any(), any(), any(), any(), any(), any());
        verify(assignments, never()).save(any());
    }

    @Test
    void assignSalesEvent_withNoTerms_isNoCapAndZeroCommission() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), null, null);

        ArgumentCaptor<EventSalesAssignment> saved = ArgumentCaptor.forClass(EventSalesAssignment.class);
        verify(sales).save(saved.capture());
        assertThat(saved.getValue().getTicketCap()).isNull();
        assertThat(saved.getValue().getCommissionPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void assignSalesEvent_again_replacesTheTerms_andSendsNothing() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        EventSalesAssignment existing = sale(s, e, 200, "5.00");
        when(sales.findByScannerIdAndEventId(s.getScannerId(), e.getEventId())).thenReturn(Optional.of(existing));

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), null, new BigDecimal("7.5"));

        verify(sales).save(existing);
        assertThat(existing.getTicketCap()).as("null lifts the cap").isNull();
        assertThat(existing.getCommissionPercent()).isEqualByComparingTo("7.50");
        verify(scanners, never()).claimInviteNotice(any(), any());
        verifyNoInteractions(inviteGateway);
    }

    @Test
    void assignSalesEvent_sameTermsAgain_writesNothing() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        EventSalesAssignment existing = sale(s, e, 200, "5.00");
        when(sales.findByScannerIdAndEventId(s.getScannerId(), e.getEventId())).thenReturn(Optional.of(existing));

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), 200, new BigDecimal("5"));

        verify(sales, never()).save(any());
    }

    @Test
    void assignSalesEvent_invalidTerms_areRefused_beforeAnyRead() {
        Map<Integer, String> caps = Map.of(0, "ticketCap", -3, "ticketCap");
        caps.forEach((cap, field) -> {
            Throwable thrown = catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", UUID.randomUUID(),
                    UUID.randomUUID(), cap, null));
            assertThat(thrown).isInstanceOf(FieldErrorsException.class);
            assertThat(((FieldErrorsException) thrown).getErrors()).containsEntry(field, "Ticket cap must be at least 1");
        });
        for (String bad : List.of("-0.01", "100.01", "250")) {
            Throwable thrown = catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", UUID.randomUUID(),
                    UUID.randomUUID(), null, new BigDecimal(bad)));
            assertThat(((FieldErrorsException) thrown).getErrors())
                    .containsEntry("commissionPercent", "Commission must be between 0 and 100");
        }
        Throwable thrown = catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", UUID.randomUUID(),
                UUID.randomUUID(), null, new BigDecimal("5.125")));
        assertThat(((FieldErrorsException) thrown).getErrors())
                .containsEntry("commissionPercent", "Commission can have at most 2 decimals");
        verifyNoInteractions(scanners, sales);
    }

    @Test
    void validTerms_includeTheBounds_andTrailingZeros() {
        assertThat(ScannerService.requireValidTerms(1, new BigDecimal("0"))).isEqualByComparingTo("0.00");
        assertThat(ScannerService.requireValidTerms(null, new BigDecimal("100"))).isEqualByComparingTo("100.00");
        assertThat(ScannerService.requireValidTerms(null, new BigDecimal("12.500")).toPlainString()).isEqualTo("12.50");
    }

    @Test
    void unassignSalesEvent_isOwnershipChecked_andIdempotent() {
        assertProblem(catchThrowable(() -> service.unassignSalesEvent(ORGANIZER, "org", UUID.randomUUID(),
                UUID.randomUUID())), HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);

        EventScanner s = agent(ORGANIZER);
        owns(s);
        UUID eventId = UUID.randomUUID();
        when(sales.deleteByScannerIdAndEventId(s.getScannerId(), eventId)).thenReturn(1, 0);

        service.unassignSalesEvent(ORGANIZER, "org", s.getScannerId(), eventId);
        service.unassignSalesEvent(ORGANIZER, "org", s.getScannerId(), eventId);

        verify(sales, times(2)).deleteByScannerIdAndEventId(s.getScannerId(), eventId);
    }

    @Test
    void theResponse_listsSellingEventsWithTheirTerms_apartFromScanningOnes() {
        EventScanner s = member(ORGANIZER, true, true);
        owns(s);
        Event scanned = event(ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(5));
        Event sold = upcoming(ORGANIZER);
        Event deleted = upcoming(ORGANIZER);
        deleted.setDeleted(true);
        when(assignments.findByScannerIdIn(any())).thenReturn(List.of(scanRow(s, scanned)));
        when(sales.findByScannerIdIn(any())).thenReturn(List.of(sale(s, sold, 200, "5.00"), sale(s, deleted, null, "0.00")));
        when(events.findAllById(any())).thenReturn(List.of(scanned, sold, deleted));

        ScannerResponseDTO response = service.get(ORGANIZER, s.getScannerId());

        assertThat(response.events()).extracting(ScannerEventDTO::eventId).containsExactly(scanned.getEventId());
        assertThat(response.salesEvents()).singleElement().satisfies(v -> {
            assertThat(v.eventId()).isEqualTo(sold.getEventId());
            assertThat(v.ticketCap()).isEqualTo(200);
            assertThat(v.commissionPercent()).isEqualByComparingTo("5.00");
        });
    }

    // ----------------------------------------------------------- invite message

    @Test
    void assignSalesEvent_firstAssignmentOfAnInvitedAgent_messagesThem_asASalesAgent() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.claimInviteNotice(s.getScannerId(), NOW)).thenReturn(1);
        when(organizerGateway.organizersByUserUuids(List.of(ORGANIZER)))
                .thenReturn(Map.of(ORGANIZER, organizer("Harare Arena Events")));

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), 200, null);

        InOrder order = inOrder(sales, scanners, inviteGateway);
        order.verify(sales).save(any(EventSalesAssignment.class));
        order.verify(scanners).claimInviteNotice(s.getScannerId(), NOW);
        order.verify(inviteGateway).sendInvite(s.getScannerId(), PHONE, "Harare Arena Events", "Jazz on the Lawn",
                List.of("SELL"));
    }

    @Test
    void theFirstAssignmentOfAScanAndSellRow_namesBothRoles_whicheverKindItIs() {
        EventScanner s = member(ORGANIZER, true, true);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.claimInviteNotice(s.getScannerId(), NOW)).thenReturn(1);

        service.assign(ORGANIZER, "org", s.getScannerId(), e.getEventId());

        verify(inviteGateway).sendInvite(s.getScannerId(), PHONE, null, "Jazz on the Lawn", List.of("SCAN", "SELL"));
    }

    @Test
    void assignSalesEvent_whenTheRowWasAlreadyTold_sendsNothing() {
        // A row first assigned to scan was told then; its first sale assignment
        // finds the claim taken and sends nothing more.
        EventScanner s = member(ORGANIZER, true, true);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.claimInviteNotice(s.getScannerId(), NOW)).thenReturn(0);

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), null, null);

        verifyNoInteractions(inviteGateway);
    }

    @Test
    void assignSalesEvent_theMessageGoesAfterTheCommit_andARollbackSendsNone() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service.setTransactionManager(tm);
        EventScanner s = agent(ORGANIZER);
        owns(s);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.claimInviteNotice(s.getScannerId(), NOW)).thenReturn(1);

        service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), null, null);

        InOrder order = inOrder(tm, scanners, inviteGateway);
        order.verify(scanners).claimInviteNotice(s.getScannerId(), NOW);
        order.verify(tm).commit(any());
        order.verify(inviteGateway).sendInvite(eq(s.getScannerId()), eq(PHONE), any(), any(), eq(List.of("SELL")));

        clearInvocations(inviteGateway);
        doThrow(new org.springframework.transaction.TransactionSystemException("commit failed"))
                .when(tm).commit(any());
        catchThrowable(() -> service.assignSalesEvent(ORGANIZER, "org", s.getScannerId(), e.getEventId(), null, null));
        verifyNoInteractions(inviteGateway);
    }

    @Test
    void enable_anInvitedAgent_isToldNamingItsSoonestSellingEvent_notALeftoverScanEvent() {
        EventScanner s = agent(ORGANIZER);
        s.setDisabledAt(NOW.minusDays(1));
        owns(s);
        Event leftoverScan = event(ORGANIZER, NOW.plusHours(2), NOW.plusHours(4));
        leftoverScan.setTitle("Peterhouse Sixth Form Ball");
        Event later = event(ORGANIZER, NOW.plusDays(9), NOW.plusDays(9).plusHours(4));
        later.setTitle("Later Gig");
        Event sooner = upcoming(ORGANIZER);
        when(assignments.findByScannerIdIn(any())).thenReturn(List.of(scanRow(s, leftoverScan)));
        when(sales.findByScannerIdIn(any())).thenReturn(List.of(sale(s, later, null, "0"), sale(s, sooner, null, "0")));
        when(events.findAllById(any())).thenReturn(List.of(leftoverScan, later, sooner));
        when(scanners.claimInviteNotice(s.getScannerId(), NOW)).thenReturn(1);

        service.enable(ORGANIZER, "org", s.getScannerId());

        verify(inviteGateway).sendInvite(s.getScannerId(), PHONE, null, "Jazz on the Lawn", List.of("SELL"));
    }

    // -------------------------------------------------------------- portal access

    @Test
    void grantPortalAccess_asksUserServiceWithNoTransactionOpen_thenRecordsTheInvite() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service.setTransactionManager(tm);
        EventScanner s = agent(ORGANIZER);
        owns(s);
        when(organizerGateway.organizersByUserUuids(List.of(ORGANIZER)))
                .thenReturn(Map.of(ORGANIZER, organizer("Harare Arena Events")));
        when(portalGateway.invite(any(), any(), any(), any(), any()))
                .thenReturn(new SalesAgentInviteGateway.Sent("2026-11-09T10:00:00Z"));

        ScannerResponseDTO response = service.grantPortalAccess(ORGANIZER, "org", s.getScannerId(), "  " + EMAIL + " ");

        InOrder order = inOrder(portalGateway, tm, scanners);
        order.verify(portalGateway).invite(s.getScannerId(), ORGANIZER, "Harare Arena Events", PHONE, EMAIL);
        order.verify(tm).getTransaction(any());
        order.verify(scanners).save(s);
        order.verify(tm).commit(any());
        verify(tm, times(1)).getTransaction(any());
        assertThat(response.portalEmail()).isEqualTo(EMAIL);
        assertThat(response.portalInvitedAt()).isEqualTo(NOW);
    }

    @Test
    void grantPortalAccess_aUserServiceRefusal_passesItsCodeThrough_andStoresNothing() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        when(portalGateway.invite(any(), any(), any(), any(), any())).thenReturn(new SalesAgentInviteGateway.Refused(
                "email_domain_reserved",
                "InnBucks staff addresses can't be used here. Your administrator will invite you.", "email"));

        Throwable thrown = catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", s.getScannerId(),
                "tendai@innbucks.co.zw"));

        assertProblem(thrown, HttpStatus.BAD_REQUEST, "email_domain_reserved");
        assertThat(thrown.getMessage())
                .isEqualTo("InnBucks staff addresses can't be used here. Your administrator will invite you.");
        assertThat(((ApiProblemException) thrown).getExtra()).containsEntry("field", "email");
        verify(scanners, never()).save(any());
        assertThat(s.getPortalEmail()).isNull();
    }

    @Test
    void grantPortalAccess_noAnswer_is503_andStoresNothing() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        when(portalGateway.invite(any(), any(), any(), any(), any()))
                .thenReturn(new SalesAgentInviteGateway.Unavailable("http_503"));

        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", s.getScannerId(), EMAIL)),
                HttpStatus.SERVICE_UNAVAILABLE, ScannerService.PORTAL_INVITE_UNAVAILABLE);
        verify(scanners, never()).save(any());
    }

    @Test
    void grantPortalAccess_withoutTheClient_is503() {
        service.setSalesAgentInviteGateway(null);
        EventScanner s = agent(ORGANIZER);
        owns(s);
        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", s.getScannerId(), EMAIL)),
                HttpStatus.SERVICE_UNAVAILABLE, ScannerService.PORTAL_INVITE_UNAVAILABLE);
    }

    @Test
    void grantPortalAccess_needsASwitchedOnAgentWhoDidNotDecline() {
        EventScanner scanOnly = member(ORGANIZER, true, false);
        EventScanner off = agent(ORGANIZER);
        off.setDisabledAt(NOW.minusDays(1));
        EventScanner declined = agent(ORGANIZER);
        declined.setDeclinedAt(NOW.minusDays(1));
        owns(scanOnly);
        owns(off);
        owns(declined);

        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", scanOnly.getScannerId(), EMAIL)),
                HttpStatus.CONFLICT, ScannerService.NOT_A_SALES_AGENT);
        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", off.getScannerId(), EMAIL)),
                HttpStatus.CONFLICT, ScannerService.SCANNER_DISABLED);
        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", declined.getScannerId(), EMAIL)),
                HttpStatus.CONFLICT, ScannerService.SCANNER_DECLINED);
        assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", UUID.randomUUID(), EMAIL)),
                HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);
        verifyNoInteractions(portalGateway);
    }

    @Test
    void grantPortalAccess_aBlankOrMalformedAddress_isRefusedHere_withoutAsking() {
        EventScanner s = agent(ORGANIZER);
        owns(s);
        for (String bad : new String[] {null, "  ", "not-an-address", "a b@example.com", "x".repeat(250) + "@a.co",
                "tendai\u0007@gmail.com"}) {
            assertProblem(catchThrowable(() -> service.grantPortalAccess(ORGANIZER, "org", s.getScannerId(), bad)),
                    HttpStatus.BAD_REQUEST, ScannerService.INVALID_EMAIL);
        }
        verifyNoInteractions(portalGateway);
    }

    @Test
    void revokePortalAccess_clearsTheRecord_thenRevokesTheInvites() {
        EventScanner s = agent(ORGANIZER);
        s.setPortalEmail(EMAIL);
        s.setPortalInvitedAt(NOW.minusDays(1));
        owns(s);

        ScannerResponseDTO response = service.revokePortalAccess(ORGANIZER, "org", s.getScannerId());

        InOrder order = inOrder(scanners, portalGateway);
        order.verify(scanners).save(s);
        order.verify(portalGateway).revoke(s.getScannerId());
        assertThat(response.portalEmail()).isNull();
        assertThat(response.portalInvitedAt()).isNull();
    }

    @Test
    void revokePortalAccess_withNothingRecorded_stillRevokes_soAFailedRevokeCanBeRetried() {
        EventScanner s = agent(ORGANIZER);
        owns(s);

        service.revokePortalAccess(ORGANIZER, "org", s.getScannerId());

        verify(scanners, never()).save(any());
        verify(portalGateway).revoke(s.getScannerId());
    }

    @Test
    void revokePortalAccess_someoneElsesMember_isNotFound_andRevokesNothing() {
        assertProblem(catchThrowable(() -> service.revokePortalAccess(ORGANIZER, "org", UUID.randomUUID())),
                HttpStatus.NOT_FOUND, ScannerService.SCANNER_NOT_FOUND);
        verifyNoInteractions(portalGateway);
    }

    // ---------------------------------------------------------------------- gate

    @Test
    void gate_anAcceptedSellOnlyRow_isNoScanningTeam() {
        EventScanner s = accepted(agent(ORGANIZER), ACCOUNT);
        when(scanners.findActiveForAccount(ACCOUNT)).thenReturn(List.of(s));

        GateResponseDTO gate = service.gate(ACCOUNT, PHONE);

        assertThat(gate.events()).isEmpty();
        assertThat(gate.invites()).isEmpty();
        verifyNoInteractions(organizerGateway);
    }

    @Test
    void gate_invitesSayWhatTheyAreFor_andListTheEventsOfEachKind() {
        EventScanner both = member(ORGANIZER, true, true);
        EventScanner sellOnly = agent(OTHER_ORGANIZER);
        Event toScan = event(ORGANIZER, NOW.plusDays(1), NOW.plusDays(1).plusHours(4));
        Event toSell = upcoming(ORGANIZER);
        Event otherSell = upcoming(OTHER_ORGANIZER);
        Event leftoverScan = event(OTHER_ORGANIZER, NOW.plusDays(2), NOW.plusDays(2).plusHours(4));
        when(scanners.findPendingForPhone(PHONE)).thenReturn(List.of(both, sellOnly));
        when(assignments.findByScannerIdIn(any())).thenReturn(List.of(scanRow(both, toScan), scanRow(sellOnly, leftoverScan)));
        when(sales.findByScannerIdIn(any())).thenReturn(List.of(sale(both, toSell, null, "0"), sale(sellOnly, otherSell, 5, "2")));
        when(events.findAllById(any())).thenReturn(List.of(toScan, toSell, otherSell, leftoverScan));

        GateResponseDTO gate = service.gate(ACCOUNT, PHONE);

        assertThat(gate.events()).as("nothing accepted yet").isEmpty();
        assertThat(gate.invites()).hasSize(2);
        GateInviteDTO first = gate.invites().get(0);
        assertThat(first.canScan()).isTrue();
        assertThat(first.canSell()).isTrue();
        assertThat(first.events()).extracting(ScannerEventDTO::eventId).containsExactly(toScan.getEventId());
        assertThat(first.salesEvents()).extracting(ScannerEventDTO::eventId).containsExactly(toSell.getEventId());
        GateInviteDTO second = gate.invites().get(1);
        assertThat(second.canScan()).isFalse();
        assertThat(second.canSell()).isTrue();
        assertThat(second.events()).as("a sell-only invite lists no scanning").isEmpty();
        assertThat(second.salesEvents()).extracting(ScannerEventDTO::eventId).containsExactly(otherSell.getEventId());
    }

    // ------------------------------------------------------------- agent's list

    @Test
    void sales_needsAnAccountOnTheSession() {
        assertProblem(catchThrowable(() -> service.sales(null)), HttpStatus.BAD_REQUEST, ScannerService.ACCOUNT_REQUIRED);
        verifyNoInteractions(scanners);
    }

    @Test
    void sales_forSomeoneWhoSellsNothing_isEmpty_andAsksNoNames() {
        assertThat(service.sales(ACCOUNT).events()).isEmpty();
        verifyNoInteractions(organizerGateway);
    }

    @Test
    void sales_listsUpcomingSellingEvents_soonestFirst_withTerms_andDropsEndedDeletedAndForeignOnes() {
        EventScanner atA = accepted(agent(ORGANIZER), ACCOUNT);
        EventScanner atB = accepted(member(OTHER_ORGANIZER, true, true), ACCOUNT);
        Event live = event(ORGANIZER, NOW.minusHours(1), NOW.plusHours(3));
        Event later = event(OTHER_ORGANIZER, NOW.plusDays(2), NOW.plusDays(2).plusHours(4));
        Event ended = event(ORGANIZER, NOW.minusDays(1), NOW.minusDays(1).plusHours(3));
        Event deleted = event(ORGANIZER, NOW.plusDays(3), NOW.plusDays(3).plusHours(2));
        deleted.setDeleted(true);
        Event foreign = event(OTHER_ORGANIZER, NOW.plusDays(4), NOW.plusDays(4).plusHours(2));
        when(scanners.findActiveSellersForAccount(ACCOUNT)).thenReturn(List.of(atA, atB));
        when(sales.findByScannerIdIn(any())).thenReturn(List.of(
                sale(atB, later, null, "0.00"), sale(atA, live, 200, "5.00"), sale(atA, ended, null, "0"),
                sale(atA, deleted, null, "0"), sale(atA, foreign, null, "0")));
        when(events.findAllById(any())).thenReturn(List.of(live, later, ended, deleted, foreign));
        when(organizerGateway.organizersByUserUuids(any())).thenReturn(Map.of(
                ORGANIZER, organizer("Harare Arena Events"), OTHER_ORGANIZER, organizer("Bulawayo Live")));

        AgentSalesResponseDTO list = service.sales(ACCOUNT);

        assertThat(list.events()).extracting(AgentSalesEventDTO::eventId)
                .containsExactly(live.getEventId(), later.getEventId());
        AgentSalesEventDTO first = list.events().get(0);
        assertThat(first.agentId()).isEqualTo(atA.getScannerId());
        assertThat(first.organizerName()).isEqualTo("Harare Arena Events");
        assertThat(first.ticketCap()).isEqualTo(200);
        assertThat(first.commissionPercent()).isEqualByComparingTo("5.00");
        assertThat(list.events().get(1).agentId()).isEqualTo(atB.getScannerId());
        assertThat(list.events().get(1).ticketCap()).isNull();
    }

    @Test
    void sales_dropsAnyRowTheQueryShouldNotHaveReturned() {
        EventScanner otherAccount = accepted(agent(ORGANIZER), UUID.randomUUID());
        EventScanner notSelling = accepted(member(ORGANIZER, true, false), ACCOUNT);
        EventScanner off = accepted(agent(ORGANIZER), ACCOUNT);
        off.setDisabledAt(NOW.minusHours(1));
        EventScanner invited = agent(ORGANIZER);
        when(scanners.findActiveSellersForAccount(ACCOUNT)).thenReturn(List.of(otherAccount, notSelling, off, invited));

        assertThat(service.sales(ACCOUNT).events()).isEmpty();
        verifyNoInteractions(sales, organizerGateway);
    }

    // --------------------------------------------------------- scanner check

    @Test
    void access_aSellOnlyRow_cannotScan_evenWithALeftoverScanAssignment() {
        EventScanner s = accepted(agent(ORGANIZER), ACCOUNT);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.findActiveForAccount(ACCOUNT)).thenReturn(List.of(s));
        when(assignments.existsByScannerIdAndEventId(s.getScannerId(), e.getEventId())).thenReturn(true);

        assertThat(service.access(ACCOUNT, e.getEventId()).allowed()).isFalse();
    }

    // ---------------------------------------------------------- seller check

    /** An accepted, switched-on agent of ORGANIZER assigned to an upcoming event of ORGANIZER; then {@code change}. */
    private SellerAccessDTO sellerAccessAfter(Consumer<SellerFixture> change) {
        SellerFixture f = new SellerFixture();
        f.agent = accepted(agent(ORGANIZER), ACCOUNT);
        f.event = upcoming(ORGANIZER);
        f.terms = sale(f.agent, f.event, 200, "5.00");
        change.accept(f);
        when(events.findById(f.event.getEventId())).thenReturn(Optional.of(f.event));
        when(scanners.findActiveSellersForAccount(ACCOUNT)).thenReturn(List.of(f.agent));
        when(sales.findByScannerIdAndEventId(f.agent.getScannerId(), f.event.getEventId()))
                .thenReturn(Optional.ofNullable(f.terms));
        return service.sellerAccess(f.account, f.event.getEventId());
    }

    private static final class SellerFixture {
        EventScanner agent;
        Event event;
        EventSalesAssignment terms;
        UUID account = ACCOUNT;
    }

    @Test
    void sellerAccess_anAssignedAgent_isAllowed_withTheTerms() {
        SellerAccessDTO access = sellerAccessAfter(f -> { });

        assertThat(access.allowed()).isTrue();
        assertThat(access.organizerUuid()).isEqualTo(ORGANIZER);
        assertThat(access.displayName()).isEqualTo("Tendai Moyo");
        assertThat(access.ticketCap()).isEqualTo(200);
        assertThat(access.commissionPercent()).isEqualByComparingTo("5.00");
        assertThat(access.agentId()).isNotNull();
    }

    @Test
    void sellerAccess_endedEvent_isStillAnswered_bookingServiceOwnsTheSalesWindow() {
        assertThat(sellerAccessAfter(f -> {
            f.event.setStartDateTime(NOW.minusDays(1));
            f.event.setEndDateTime(NOW.minusHours(1));
        }).allowed()).isTrue();
    }

    @Test
    void sellerAccess_isDenied_inEveryOtherCase_withEveryFieldNull() {
        Map<String, Consumer<SellerFixture>> cases = new java.util.LinkedHashMap<>();
        cases.put("not accepted", f -> {
            f.agent.setAcceptedAt(null);
            f.agent.setAccountUserUuid(null);
        });
        cases.put("declined", f -> f.agent.setDeclinedAt(NOW.minusHours(1)));
        cases.put("switched off", f -> f.agent.setDisabledAt(NOW.minusHours(1)));
        cases.put("Sell taken away", f -> f.agent.setCanSell(false));
        cases.put("unassigned", f -> f.terms = null);
        cases.put("another organizer's event", f -> f.event.setTenantUserUuid(OTHER_ORGANIZER));
        cases.put("deleted event", f -> f.event.setDeleted(true));
        cases.put("event with no organizer", f -> f.event.setTenantUserUuid(null));
        cases.put("another account", f -> f.account = UUID.randomUUID());
        cases.forEach((name, change) -> {
            setUp();
            assertThat(sellerAccessAfter(change)).as(name).isEqualTo(SellerAccessDTO.denied());
        });
    }

    @Test
    void sellerAccess_noAccountOrEvent_orAnUnknownEvent_isDenied() {
        assertThat(service.sellerAccess(null, UUID.randomUUID())).isEqualTo(SellerAccessDTO.denied());
        assertThat(service.sellerAccess(ACCOUNT, null)).isEqualTo(SellerAccessDTO.denied());
        assertThat(service.sellerAccess(ACCOUNT, UUID.randomUUID())).isEqualTo(SellerAccessDTO.denied());
        verify(scanners, never()).findActiveSellersForAccount(any());
    }

    @Test
    void sellerAccess_ofTwoTeams_answersForTheEventsOrganizersTeam() {
        EventScanner atOther = accepted(agent(OTHER_ORGANIZER), ACCOUNT);
        EventScanner atOrganizer = accepted(agent(ORGANIZER), ACCOUNT);
        Event e = upcoming(ORGANIZER);
        exists(e);
        when(scanners.findActiveSellersForAccount(ACCOUNT)).thenReturn(List.of(atOther, atOrganizer));
        when(sales.findByScannerIdAndEventId(atOrganizer.getScannerId(), e.getEventId()))
                .thenReturn(Optional.of(sale(atOrganizer, e, null, "2.50")));

        SellerAccessDTO access = service.sellerAccess(ACCOUNT, e.getEventId());

        assertThat(access.allowed()).isTrue();
        assertThat(access.agentId()).isEqualTo(atOrganizer.getScannerId());
        assertThat(access.ticketCap()).isNull();
        verify(sales, never()).findByScannerIdAndEventId(eq(atOther.getScannerId()), any());
    }
}
