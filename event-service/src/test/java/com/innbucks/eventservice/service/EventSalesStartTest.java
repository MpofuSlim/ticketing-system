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
import com.innbucks.eventservice.dto.SalesWindowDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scheduled ticket sales (V15): the stored time, the one status rule, and the
 * internal sales window booking-service asks before every sale.
 */
class EventSalesStartTest {

    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    private final EventRepository repo = mock(EventRepository.class);
    private final EventService service = new EventService(repo, new MarketTimeZone("ZW"), new EventMapper(),
            mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class),
            mock(BookingNotificationGateway.class), mock(OrganizerNotificationGateway.class));

    // ---- the rule ----------------------------------------------------------

    @Test
    void status_followsTheOneRule_atItsBoundaries() {
        LocalDateTime start = NOW.plusDays(1);
        LocalDateTime end = NOW.plusDays(10);

        assertEquals(SalesStatus.ON_SALE, SalesStatus.of(true, false, null, end, NOW),
                "no schedule: on sale as soon as published");
        assertEquals(SalesStatus.NOT_YET_ON_SALE, SalesStatus.of(true, false, start, end, NOW));
        assertEquals(SalesStatus.ON_SALE, SalesStatus.of(true, false, start, end, start),
                "sales open AT the scheduled instant");
        assertEquals(SalesStatus.ENDED, SalesStatus.of(true, false, start, end, end),
                "sales close AT the event's end");
        assertEquals(SalesStatus.ENDED, SalesStatus.of(true, false, null, end, end.plusSeconds(1)));
    }

    @Test
    void status_unpublishedWinsOverEverything() {
        LocalDateTime end = NOW.plusDays(1);
        assertEquals(SalesStatus.NOT_PUBLISHED, SalesStatus.of(false, false, null, end, NOW), "draft");
        assertEquals(SalesStatus.NOT_PUBLISHED, SalesStatus.of(true, true, null, end, NOW), "rejected");
        assertEquals(SalesStatus.NOT_PUBLISHED, SalesStatus.of(false, false, null, NOW.minusDays(1), NOW),
                "an ended draft is still a draft: never leak that it exists through ENDED");
    }

    // ---- create -------------------------------------------------------------

    @Test
    void create_storesTheTypedLocalSalesTimeAsUtc_andAnswersItsStatus() {
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateEventRequestDTO req = createRequest();
        req.setSalesStartDateTime(LocalDateTime.of(2099, 11, 14, 10, 0));

        EventResponseDTO dto = service.createEvent(ORGANIZER, "Zimbabwe", req);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertEquals(LocalDateTime.of(2099, 11, 14, 8, 0), saved.getValue().getSalesStartAt(),
                "10:00 Harare is stored as 08:00 UTC, like the event's own times");
        assertEquals(LocalDateTime.of(2099, 11, 14, 8, 0), dto.getSalesStartDateTime());
        assertEquals(SalesStatus.NOT_PUBLISHED, dto.getSalesStatus(), "a new event is a draft");
    }

    @Test
    void create_withoutASalesTime_storesNone() {
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createEvent(ORGANIZER, "Zimbabwe", createRequest());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertNull(saved.getValue().getSalesStartAt());
    }

    @Test
    void create_refusesSalesThatWouldOpenAtOrAfterTheEnd_andSavesNothing() {
        CreateEventRequestDTO atEnd = createRequest();
        atEnd.setSalesStartDateTime(atEnd.getEndDateTime());
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.createEvent(ORGANIZER, "Zimbabwe", atEnd));
        assertEquals("Ticket sales must open before the event ends.", refused.getMessage());

        CreateEventRequestDTO after = createRequest();
        after.setSalesStartDateTime(after.getEndDateTime().plusMinutes(1));
        assertThrows(BadRequestException.class, () -> service.createEvent(ORGANIZER, "Zimbabwe", after));

        verify(repo, never()).save(any());
    }

    @Test
    void create_acceptsSalesAfterTheStart_forDoorSales() {
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateEventRequestDTO req = createRequest();
        req.setSalesStartDateTime(req.getStartDateTime().plusHours(1));

        assertDoesNotThrow(() -> service.createEvent(ORGANIZER, "Zimbabwe", req));
    }

    // ---- update -------------------------------------------------------------

    @Test
    void update_setsClearsOrLeavesTheSchedule_byWhetherTheFieldWasSent() {
        Event event = publishedEvent(NOW.plusDays(5), NOW.plusDays(30));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEventRequestDTO untouched = new UpdateEventRequestDTO();
        untouched.setTitle("Renamed");
        service.updateEvent(ORGANIZER, event.getEventId(), untouched);
        assertEquals(NOW.plusDays(5), event.getSalesStartAt(), "not sent: unchanged");

        UpdateEventRequestDTO moved = new UpdateEventRequestDTO();
        LocalDateTime typedInHarare = NOW.plusDays(10).withNano(0);
        moved.setSalesStartDateTime(typedInHarare);
        service.updateEvent(ORGANIZER, event.getEventId(), moved);
        assertEquals(typedInHarare.minusHours(2), event.getSalesStartAt(), "local time, stored as UTC");

        UpdateEventRequestDTO cleared = new UpdateEventRequestDTO();
        cleared.setSalesStartDateTime(null);
        EventResponseDTO dto = service.updateEvent(ORGANIZER, event.getEventId(), cleared);
        assertNull(event.getSalesStartAt(), "sent as null: the schedule is removed");
        assertEquals(SalesStatus.ON_SALE, dto.getSalesStatus());
    }

    @Test
    void update_refusesMovingTheEndBeforeAStoredSalesTime() {
        Event event = publishedEvent(NOW.plusDays(5), NOW.plusDays(30));
        event.setStartDateTime(NOW.plusDays(1));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        // 2 days from now in UTC, typed as Harare time (UTC+2): after the start,
        // but before the stored sales time.
        req.setEndDateTime(NOW.plusDays(2).plusHours(2));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.updateEvent(ORGANIZER, event.getEventId(), req));
        assertEquals("Ticket sales must open before the event ends.", refused.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateRequest_tellsAnExplicitNullApartFromAnAbsentField() throws Exception {
        JsonMapper json = JsonMapper.builder().build();

        UpdateEventRequestDTO absent = json.readValue("{\"title\":\"T\"}", UpdateEventRequestDTO.class);
        assertFalse(absent.isSalesStartDateTimeSent());

        UpdateEventRequestDTO explicitNull = json.readValue("{\"salesStartDateTime\":null}",
                UpdateEventRequestDTO.class);
        assertTrue(explicitNull.isSalesStartDateTimeSent());
        assertNull(explicitNull.getSalesStartDateTime());

        UpdateEventRequestDTO value = json.readValue("{\"salesStartDateTime\":\"2026-11-14T10:00:00\"}",
                UpdateEventRequestDTO.class);
        assertTrue(value.isSalesStartDateTimeSent());
        assertEquals(LocalDateTime.of(2026, 11, 14, 10, 0), value.getSalesStartDateTime());

        UpdateEventRequestDTO smuggled = json.readValue(
                "{\"salesStartDateTimeSent\":true}", UpdateEventRequestDTO.class);
        assertFalse(smuggled.isSalesStartDateTimeSent(), "the flag is never bound from the request");
    }

    // ---- the internal sales window -----------------------------------------

    @Test
    void salesWindow_answersEveryStatus_withTheOrganizer() {
        Event onSale = publishedEvent(null, NOW.plusDays(3));
        Event early = publishedEvent(NOW.plusDays(1), NOW.plusDays(3));
        Event ended = publishedEvent(null, NOW.minusMinutes(1));
        Event draft = publishedEvent(null, NOW.plusDays(3));
        draft.setActive(false);
        Event rejected = publishedEvent(null, NOW.plusDays(3));
        rejected.setRejected(true);
        for (Event e : new Event[]{onSale, early, ended, draft, rejected}) {
            when(repo.findByEventIdAndDeletedFalse(e.getEventId())).thenReturn(Optional.of(e));
        }

        assertEquals("ON_SALE", service.salesWindow(onSale.getEventId()).getStatus());
        SalesWindowDTO earlyWindow = service.salesWindow(early.getEventId());
        assertEquals("NOT_YET_ON_SALE", earlyWindow.getStatus());
        assertEquals(early.getSalesStartAt(), earlyWindow.getSalesStartDateTime());
        assertEquals(ORGANIZER, earlyWindow.getTenantUserUuid(),
                "booking-service stamps the organizer from this answer");
        assertEquals("ENDED", service.salesWindow(ended.getEventId()).getStatus());
        assertEquals("NOT_PUBLISHED", service.salesWindow(draft.getEventId()).getStatus());
        assertEquals("NOT_PUBLISHED", service.salesWindow(rejected.getEventId()).getStatus());
    }

    @Test
    void salesWindow_unknownOrDeletedEvent_isAnAnswer_notAnError() {
        UUID missing = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(missing)).thenReturn(Optional.empty());

        SalesWindowDTO window = service.salesWindow(missing);

        assertEquals("NOT_FOUND", window.getStatus());
        assertEquals(missing, window.getEventId());
        assertNull(window.getTenantUserUuid());
    }

    // ---- the cached list page ----------------------------------------------

    @Test
    void cachedSnapshot_keepsTheSalesTime_butWorksTheStatusOutAgainOnEveryRead() {
        Event event = publishedEvent(NOW.minusMinutes(1), NOW.plusDays(3));
        EventSnapshot snapshot = EventSnapshot.of(new EventMapper().toDTO(event));

        assertEquals(event.getSalesStartAt(), snapshot.salesStartDateTime());
        EventResponseDTO fresh = snapshot.toDto();
        assertEquals(SalesStatus.ON_SALE, fresh.getSalesStatus());

        Event later = publishedEvent(NOW.plusDays(1), NOW.plusDays(3));
        assertEquals(SalesStatus.NOT_YET_ON_SALE,
                EventSnapshot.of(new EventMapper().toDTO(later)).toDto().getSalesStatus());
    }

    // ---- fixtures -----------------------------------------------------------

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
