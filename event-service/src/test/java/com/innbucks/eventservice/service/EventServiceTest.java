package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Province;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventServiceTest {

    @Test
    void updateEvent_rejectsWhenTenantDoesNotMatch() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantId("owner-tenant")
                .title("Old")
                .venue("Venue")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build();

        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateEvent("other-tenant", eventId, new UpdateEventRequestDTO()));

        assertEquals("You are not authorized to update this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_adjustsAvailableTicketsByCapacityDiff() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantId("tenant-1")
                .title("Old")
                .venue("Venue")
                .province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(100)
                .availableTickets(60)
                .deleted(false)
                .build();

        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toDTO(any(Event.class))).thenReturn(null);

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setTotalCapacity(120); // diff +20

        service.updateEvent("tenant-1", eventId, req);

        ArgumentCaptor<Event> savedCaptor = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(savedCaptor.capture());

        Event saved = savedCaptor.getValue();
        assertEquals(120, saved.getTotalCapacity());
        assertEquals(80, saved.getAvailableTickets()); // 60 + 20
    }

    @Test
    void createEvent_initializesAvailableTicketsToTotalCapacity() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class));

        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setDescription("desc"); req.setVenue("Venue");
        req.setProvince(Province.HRE); req.setDateTime(LocalDateTime.now().plusDays(10));
        req.setTotalCapacity(200);
        when(repo.existsByTenantIdAndTitleAndVenueAndDateTimeAndDeletedFalse(
                any(), any(), any(), any())).thenReturn(false);
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createEvent("tenant-9", req);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertEquals("tenant-9", saved.getValue().getTenantId());
        assertEquals(200, saved.getValue().getTotalCapacity());
        assertEquals(200, saved.getValue().getAvailableTickets());
        assertFalse(saved.getValue().isDeleted());
    }

    @Test
    void createEvent_rejectsDuplicateForSameTenantTitleVenueDate() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class));

        LocalDateTime when = LocalDateTime.now().plusDays(10);
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setVenue("Harare Gardens");
        req.setProvince(Province.HRE); req.setDateTime(when); req.setTotalCapacity(100);

        when(repo.existsByTenantIdAndTitleAndVenueAndDateTimeAndDeletedFalse(
                "tenant-1", "Concert", "Harare Gardens", when)).thenReturn(true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createEvent("tenant-1", req));
        assertTrue(ex.getMessage().toLowerCase().contains("already exists"));
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_asAdmin_canEditEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId).tenantId("owner").title("Old").venue("V")
                .province(Province.HRE).dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setTitle("New Title");

        service.updateEvent("admin-user", "ROLE_SUPER_ADMIN", eventId, req);

        verify(repo).save(argThat(e -> "New Title".equals(e.getTitle())));
    }

    @Test
    void deleteEvent_rejectsNonOwnerTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("owner-tenant")
                .title("T").venue("V").province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(1).availableTickets(1).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.deleteEvent("other-tenant", "ROLE_EVENT_ORGANIZER", eventId));
        assertEquals("You are not authorized to delete this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteEvent_asAdmin_softDeletesEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("owner-tenant")
                .title("T").venue("V").province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(5))
                .totalCapacity(1).availableTickets(1).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        service.deleteEvent("admin-user", "ROLE_SUPER_ADMIN", eventId);

        verify(repo).save(argThat(Event::isDeleted));
    }

    @Test
    void getEventById_throwsWhenMissing() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class));
        when(repo.findByEventIdAndDeletedFalse(any())).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getEventById(UUID.randomUUID()));
        assertEquals("Event not found", ex.getMessage());
    }

    @Test
    void getEventById_recomputesAvailableTicketsFromBookingGateway() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = new EventMapper();
        SeatCategoryGateway seats = mock(SeatCategoryGateway.class);
        BookingGateway bookings = mock(BookingGateway.class);
        EventService service = new EventService(repo, mapper, seats, bookings);

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("a").title("T")
                .venue("V").province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(1))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(seats.fetchForEvent(eventId)).thenReturn(Collections.emptyList());
        // 30 active booking items → response should report 70 tickets remaining,
        // even though the entity's stored availableTickets is still 100.
        when(bookings.activeCountsByEventIds(java.util.List.of(eventId)))
                .thenReturn(java.util.Map.of(eventId, 30L));

        EventResponseDTO result = service.getEventById(eventId);

        assertEquals(70, result.getAvailableTickets());
        assertEquals(100, result.getTotalCapacity());
    }

    @Test
    void getEventById_fallsBackToEmptySeatCategories_whenSeatServiceFails() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("a").title("T")
                .venue("V").province(Province.HRE)
                .dateTime(LocalDateTime.now().plusDays(1))
                .totalCapacity(5).availableTickets(5).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        EventResponseDTO dto = new EventResponseDTO();
        when(mapper.toDTO(existing)).thenReturn(dto);
        // Gateway swallows the seat-service failure and returns an empty list,
        // so the event is still served — just without enriched categories.
        when(gateway.fetchForEvent(eventId)).thenReturn(Collections.emptyList());

        EventResponseDTO result = service.getEventById(eventId);

        assertSame(dto, result);
        assertNotNull(result.getSeatCategories());
        assertTrue(result.getSeatCategories().isEmpty());
    }
}
