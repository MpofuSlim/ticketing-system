package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.EventSnapshot;
import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.SalesStatus;
import com.innbucks.eventservice.dto.SalesWindowChangeDTO;
import com.innbucks.eventservice.dto.SalesWindowDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Scheduled close of ticket sales (V22): the stored time, SALES_CLOSED in the
 * one status rule, the window's shape, the change record, and the internal
 * sales window booking-service asks before every sale and hold extension.
 */
class EventSalesEndTest {

    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    private final EventRepository repo = mock(EventRepository.class);
    private final EventSalesWindowChangeRepository changes = mock(EventSalesWindowChangeRepository.class);
    private final EventService service = new EventService(repo, new MarketTimeZone("ZW"), new EventMapper(),
            mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class),
            mock(BookingNotificationGateway.class), mock(OrganizerNotificationGateway.class));

    @BeforeEach
    void wire() {
        service.setSalesWindowChanges(changes);
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(changes.save(any(EventSalesWindowChange.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- the rule ----------------------------------------------------------

    @Test
    void status_salesClose_atTheScheduledInstant_andTheEventEndStillWins() {
        LocalDateTime salesEnd = NOW.plusHours(1);
        LocalDateTime end = NOW.plusHours(5);

        assertEquals(SalesStatus.ON_SALE, SalesStatus.of(true, false, null, salesEnd, end, NOW));
        assertEquals(SalesStatus.ON_SALE, SalesStatus.of(true, false, null, salesEnd, end, salesEnd.minusNanos(1)),
                "on sale up to the instant before");
        assertEquals(SalesStatus.SALES_CLOSED, SalesStatus.of(true, false, null, salesEnd, end, salesEnd),
                "closed AT the scheduled instant");
        assertEquals(SalesStatus.SALES_CLOSED, SalesStatus.of(true, false, null, salesEnd, end, end.minusNanos(1)));
        assertEquals(SalesStatus.ENDED, SalesStatus.of(true, false, null, salesEnd, end, end),
                "once the event is over it reads ENDED, as without a sales end");
        assertEquals(SalesStatus.ENDED, SalesStatus.of(true, false, null, end, end, end),
                "a sales end AT the event's end changes nothing");
    }

    @Test
    void status_unpublishedWinsOverAClosedSale() {
        LocalDateTime salesEnd = NOW.minusHours(1);
        LocalDateTime end = NOW.plusHours(5);
        assertEquals(SalesStatus.NOT_PUBLISHED, SalesStatus.of(false, false, null, salesEnd, end, NOW), "draft");
        assertEquals(SalesStatus.NOT_PUBLISHED, SalesStatus.of(true, true, null, salesEnd, end, NOW), "rejected");
    }

    @Test
    void status_aFullWindow_opensThenCloses() {
        LocalDateTime start = NOW.plusDays(1);
        LocalDateTime salesEnd = NOW.plusDays(2);
        LocalDateTime end = NOW.plusDays(3);
        assertEquals(SalesStatus.NOT_YET_ON_SALE, SalesStatus.of(true, false, start, salesEnd, end, NOW));
        assertEquals(SalesStatus.ON_SALE, SalesStatus.of(true, false, start, salesEnd, end, start));
        assertEquals(SalesStatus.SALES_CLOSED, SalesStatus.of(true, false, start, salesEnd, end, salesEnd));
    }

    // ---- create -------------------------------------------------------------

    @Test
    void create_storesTheTypedLocalSalesEndAsUtc_andRecordsIt() {
        CreateEventRequestDTO req = createRequest();
        req.setSalesEndDateTime(LocalDateTime.of(2099, 12, 5, 16, 0));

        EventResponseDTO dto = service.createEvent(ORGANIZER, "Zimbabwe", req);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertEquals(LocalDateTime.of(2099, 12, 5, 14, 0), saved.getValue().getSalesEndAt(),
                "16:00 Harare is stored as 14:00 UTC, like the event's own times");
        assertEquals(LocalDateTime.of(2099, 12, 5, 14, 0), dto.getSalesEndDateTime());

        ArgumentCaptor<EventSalesWindowChange> change = ArgumentCaptor.forClass(EventSalesWindowChange.class);
        verify(changes).save(change.capture());
        assertEquals(EventSalesWindowChange.ChangeType.CREATED, change.getValue().getChangeType());
        assertNull(change.getValue().getOldSalesEndAt());
        assertEquals(LocalDateTime.of(2099, 12, 5, 14, 0), change.getValue().getNewSalesEndAt());
        assertNull(change.getValue().getNewSalesStartAt());
    }

    @Test
    void create_withNoSalesWindow_recordsNothing() {
        service.createEvent(ORGANIZER, "Zimbabwe", createRequest());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertNull(saved.getValue().getSalesEndAt());
        verify(changes, never()).save(any());
    }

    @Test
    void create_acceptsASalesEndAtTheEventEnd_orAfterTheStart() {
        CreateEventRequestDTO atEnd = createRequest();
        atEnd.setSalesEndDateTime(atEnd.getEndDateTime());
        assertDoesNotThrow(() -> service.createEvent(ORGANIZER, "Zimbabwe", atEnd));

        CreateEventRequestDTO duringTheEvent = createRequest();
        duringTheEvent.setSalesEndDateTime(duringTheEvent.getStartDateTime().plusHours(1));
        assertDoesNotThrow(() -> service.createEvent(ORGANIZER, "Zimbabwe", duringTheEvent));
    }

    @Test
    void create_refusesASalesEndAfterTheEventEnd_andSavesNothing() {
        CreateEventRequestDTO req = createRequest();
        req.setSalesEndDateTime(req.getEndDateTime().plusMinutes(1));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.createEvent(ORGANIZER, "Zimbabwe", req));
        assertEquals("Ticket sales must close by the time the event ends.", refused.getMessage());
        verify(repo, never()).save(any());
        verify(changes, never()).save(any());
    }

    @Test
    void create_refusesSalesThatCloseAtOrBeforeTheyOpen() {
        CreateEventRequestDTO same = createRequest();
        same.setSalesStartDateTime(LocalDateTime.of(2099, 12, 1, 9, 0));
        same.setSalesEndDateTime(LocalDateTime.of(2099, 12, 1, 9, 0));
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.createEvent(ORGANIZER, "Zimbabwe", same));
        assertEquals("Ticket sales must close after they open.", refused.getMessage());

        CreateEventRequestDTO before = createRequest();
        before.setSalesStartDateTime(LocalDateTime.of(2099, 12, 1, 9, 0));
        before.setSalesEndDateTime(LocalDateTime.of(2099, 11, 30, 9, 0));
        assertThrows(BadRequestException.class, () -> service.createEvent(ORGANIZER, "Zimbabwe", before));

        verify(repo, never()).save(any());
    }

    // ---- update -------------------------------------------------------------

    @Test
    void update_setsClearsOrLeavesTheSalesEnd_byWhetherTheFieldWasSent_andRecordsEachChange() {
        Event event = publishedEvent(null, NOW.plusDays(30));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO untouched = new UpdateEventRequestDTO();
        untouched.setTitle("Renamed");
        service.updateEvent(ORGANIZER, event.getEventId(), untouched);
        assertNull(event.getSalesEndAt(), "not sent: unchanged");
        verify(changes, never()).save(any());

        UpdateEventRequestDTO closeAt = new UpdateEventRequestDTO();
        LocalDateTime typedInHarare = NOW.plusDays(10).withNano(0);
        closeAt.setSalesEndDateTime(typedInHarare);
        service.updateEvent(ORGANIZER, event.getEventId(), closeAt);
        assertEquals(typedInHarare.minusHours(2), event.getSalesEndAt(), "local time, stored as UTC");

        UpdateEventRequestDTO cleared = new UpdateEventRequestDTO();
        cleared.setSalesEndDateTime(null);
        EventResponseDTO dto = service.updateEvent(ORGANIZER, event.getEventId(), cleared);
        assertNull(event.getSalesEndAt(), "sent as null: sales close when the event ends");
        assertEquals(SalesStatus.ON_SALE, dto.getSalesStatus());

        ArgumentCaptor<EventSalesWindowChange> recorded = ArgumentCaptor.forClass(EventSalesWindowChange.class);
        verify(changes, times(2)).save(recorded.capture());
        EventSalesWindowChange set = recorded.getAllValues().get(0);
        assertEquals(EventSalesWindowChange.ChangeType.CHANGED, set.getChangeType());
        assertNull(set.getOldSalesEndAt());
        assertEquals(typedInHarare.minusHours(2), set.getNewSalesEndAt());
        EventSalesWindowChange removed = recorded.getAllValues().get(1);
        assertEquals(typedInHarare.minusHours(2), removed.getOldSalesEndAt());
        assertNull(removed.getNewSalesEndAt());
    }

    @Test
    void update_sendingTheSameSalesEndAgain_recordsNothing() {
        Event event = publishedEvent(null, NOW.plusDays(30));
        LocalDateTime typedInHarare = NOW.plusDays(10).withNano(0);
        event.setSalesEndAt(typedInHarare.minusHours(2));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO same = new UpdateEventRequestDTO();
        same.setSalesEndDateTime(typedInHarare);
        service.updateEvent(ORGANIZER, event.getEventId(), same);

        verify(changes, never()).save(any());
    }

    @Test
    void update_closingSalesNow_readsSalesClosed() {
        Event event = publishedEvent(null, NOW.plusDays(30));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO closeNow = new UpdateEventRequestDTO();
        // A minute ago, typed as Harare time (UTC+2).
        closeNow.setSalesEndDateTime(NOW.minusMinutes(1).plusHours(2));
        EventResponseDTO dto = service.updateEvent(ORGANIZER, event.getEventId(), closeNow);

        assertEquals(SalesStatus.SALES_CLOSED, dto.getSalesStatus(),
                "a time at or before now closes sales at once");
    }

    @Test
    void update_refusesMovingTheEventEndBeforeAStoredSalesEnd() {
        Event event = publishedEvent(null, NOW.plusDays(30));
        event.setStartDateTime(NOW.plusDays(1));
        event.setSalesEndAt(NOW.plusDays(20));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        // 5 days from now in UTC, typed as Harare time: after the start, before the stored sales end.
        req.setEndDateTime(NOW.plusDays(5).plusHours(2));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updateEvent(ORGANIZER, event.getEventId(), req));
        assertEquals("Ticket sales must close by the time the event ends.", refused.getMessage());
        verify(repo, never()).save(any());
        verify(changes, never()).save(any());
    }

    @Test
    void update_refusesMovingTheSalesStartPastAStoredSalesEnd() {
        Event event = publishedEvent(null, NOW.plusDays(30));
        event.setSalesEndAt(NOW.plusDays(10));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setSalesStartDateTime(NOW.plusDays(12).plusHours(2));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updateEvent(ORGANIZER, event.getEventId(), req));
        assertEquals("Ticket sales must close after they open.", refused.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateRequest_tellsAnExplicitNullApartFromAnAbsentField() throws Exception {
        JsonMapper json = JsonMapper.builder().build();

        UpdateEventRequestDTO absent = json.readValue("{\"title\":\"T\"}", UpdateEventRequestDTO.class);
        assertFalse(absent.isSalesEndDateTimeSent());

        UpdateEventRequestDTO explicitNull = json.readValue("{\"salesEndDateTime\":null}",
                UpdateEventRequestDTO.class);
        assertTrue(explicitNull.isSalesEndDateTimeSent());
        assertNull(explicitNull.getSalesEndDateTime());

        UpdateEventRequestDTO value = json.readValue("{\"salesEndDateTime\":\"2026-12-05T18:00:00\"}",
                UpdateEventRequestDTO.class);
        assertTrue(value.isSalesEndDateTimeSent());
        assertEquals(LocalDateTime.of(2026, 12, 5, 18, 0), value.getSalesEndDateTime());

        UpdateEventRequestDTO smuggled = json.readValue("{\"salesEndDateTimeSent\":true}",
                UpdateEventRequestDTO.class);
        assertFalse(smuggled.isSalesEndDateTimeSent(), "the flag is never bound from the request");
    }

    // ---- the internal sales window -----------------------------------------

    @Test
    void salesWindow_answersSalesClosed_andCarriesTheSalesEnd() {
        Event closed = publishedEvent(null, NOW.plusDays(3));
        closed.setSalesEndAt(NOW.minusMinutes(1));
        Event open = publishedEvent(null, NOW.plusDays(3));
        open.setSalesEndAt(NOW.plusDays(1));
        when(repo.findByEventIdAndDeletedFalse(closed.getEventId())).thenReturn(Optional.of(closed));
        when(repo.findByEventIdAndDeletedFalse(open.getEventId())).thenReturn(Optional.of(open));

        SalesWindowDTO closedWindow = service.salesWindow(closed.getEventId());
        assertEquals("SALES_CLOSED", closedWindow.getStatus());
        assertEquals(closed.getSalesEndAt(), closedWindow.getSalesEndDateTime());
        assertEquals(ORGANIZER, closedWindow.getTenantUserUuid());

        SalesWindowDTO openWindow = service.salesWindow(open.getEventId());
        assertEquals("ON_SALE", openWindow.getStatus());
        assertEquals(open.getSalesEndAt(), openWindow.getSalesEndDateTime());
    }

    // ---- the cached list page ----------------------------------------------

    @Test
    void cachedSnapshot_keepsTheSalesEnd_andWorksTheStatusOutAgainOnEveryRead() {
        Event event = publishedEvent(null, NOW.plusDays(3));
        event.setSalesEndAt(NOW.minusMinutes(1));

        EventSnapshot snapshot = EventSnapshot.of(new EventMapper().toDTO(event));

        assertEquals(event.getSalesEndAt(), snapshot.salesEndDateTime());
        EventResponseDTO fresh = snapshot.toDto();
        assertEquals(event.getSalesEndAt(), fresh.getSalesEndDateTime());
        assertEquals(SalesStatus.SALES_CLOSED, fresh.getSalesStatus());
    }

    // ---- the organizer's list filter ---------------------------------------

    @Test
    void myEvents_withASalesStatus_filtersInTheQuery_withNowInUtc() {
        Event closed = publishedEvent(null, NOW.plusDays(3));
        closed.setSalesEndAt(NOW.minusMinutes(1));
        when(repo.findByTenantUserUuidAndSalesStatus(eq(ORGANIZER), any(), any(), any(), anyString(), any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(closed)));

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        Page<EventResponseDTO> page = service.getMyEvents(ORGANIZER, null, null, null, SalesStatus.SALES_CLOSED,
                0, 10, "startDateTime");
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

        assertEquals(1, page.getTotalElements());
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).findByTenantUserUuidAndSalesStatus(eq(ORGANIZER), any(), any(), any(), eq("SALES_CLOSED"),
                now.capture(), any(Pageable.class));
        assertFalse(now.getValue().isBefore(before));
        assertFalse(now.getValue().isAfter(after));
        verify(repo, never()).findByTenantUserUuid(any(), any(), any(), any(), any());
    }

    @Test
    void myEvents_withoutASalesStatus_isTheUnfilteredList() {
        when(repo.findByTenantUserUuid(eq(ORGANIZER), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getMyEvents(ORGANIZER, null, null, null, null, 0, 10, "startDateTime");

        verify(repo, never()).findByTenantUserUuidAndSalesStatus(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- the change record --------------------------------------------------

    @Test
    void history_theOrganizerReadsTheirOwn_andStaffChangesReadAsInnBucks() {
        Event event = publishedEvent(null, NOW.plusDays(3));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        UUID staffUuid = UUID.randomUUID();
        when(changes.findTop200ByEventIdOrderByChangedAtDesc(event.getEventId())).thenReturn(List.of(
                change(event.getEventId(), staffUuid.toString()),
                change(event.getEventId(), ORGANIZER.toString())));

        List<SalesWindowChangeDTO> asOwner = service.salesWindowHistory(ORGANIZER, false, event.getEventId());
        assertEquals(SalesWindowChangeDTO.CHANGED_BY_INNBUCKS, asOwner.get(0).getChangedBy(),
                "the organizer never learns which staff account changed it");
        assertFalse(asOwner.get(0).isChangedByOrganizer());
        assertEquals(ORGANIZER.toString(), asOwner.get(1).getChangedBy());
        assertTrue(asOwner.get(1).isChangedByOrganizer());

        List<SalesWindowChangeDTO> asStaff = service.salesWindowHistory(null, true, event.getEventId());
        assertEquals(staffUuid.toString(), asStaff.get(0).getChangedBy(), "staff read who it was");
        assertFalse(asStaff.get(0).isChangedByOrganizer());
    }

    @Test
    void history_anotherOrganizer_orNoOrganizer_isTheSame404AsAMissingEvent() {
        Event event = publishedEvent(null, NOW.plusDays(3));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        UUID missing = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(missing)).thenReturn(Optional.empty());

        NotFoundException other = assertThrows(NotFoundException.class,
                () -> service.salesWindowHistory(UUID.randomUUID(), false, event.getEventId()));
        NotFoundException none = assertThrows(NotFoundException.class,
                () -> service.salesWindowHistory(null, false, event.getEventId()));
        NotFoundException gone = assertThrows(NotFoundException.class,
                () -> service.salesWindowHistory(ORGANIZER, false, missing));
        assertEquals(gone.getMessage(), other.getMessage());
        assertEquals(gone.getMessage(), none.getMessage());
        verify(changes, never()).findTop200ByEventIdOrderByChangedAtDesc(any());
    }

    // ---- fixtures -----------------------------------------------------------

    private static EventSalesWindowChange change(UUID eventId, String by) {
        return EventSalesWindowChange.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .changeType(EventSalesWindowChange.ChangeType.CHANGED)
                .newSalesEndAt(NOW.plusDays(1))
                .changedBy(by)
                .changedAt(NOW)
                .build();
    }

    private static CreateEventRequestDTO createRequest() {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Summer Concert");
        req.setVenue("Harare Gardens");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.of(2099, 12, 5, 18, 0));
        req.setEndDateTime(LocalDateTime.of(2099, 12, 5, 23, 0));
        req.setTotalCapacity(500);
        return req;
    }

    private static Event publishedEvent(LocalDateTime salesStartUtc, LocalDateTime endUtc) {
        return Event.builder()
                .eventId(UUID.randomUUID())
                .tenantUserUuid(ORGANIZER)
                .title("Summer Concert")
                .venue("Harare Gardens")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(endUtc.minusHours(5))
                .endDateTime(endUtc)
                .salesStartAt(salesStartUtc)
                .totalCapacity(500)
                .availableTickets(500)
                .deleted(false)
                .active(true)
                .build();
    }
}
