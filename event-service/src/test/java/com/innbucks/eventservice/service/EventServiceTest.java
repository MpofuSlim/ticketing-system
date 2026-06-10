package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
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
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantId("owner-tenant")
                .title("Old")
                .venue("Venue")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
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
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantId("tenant-1")
                .title("Old")
                .venue("Venue")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
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
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setDescription("desc"); req.setVenue("Venue");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.now().plusDays(10));
        req.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        req.setTotalCapacity(200);
        when(repo.existsByTenantIdAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                any(), any(), any(), any())).thenReturn(false);
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createEvent("tenant-9", "Zimbabwe", req);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertEquals("tenant-9", saved.getValue().getTenantId());
        assertEquals("Zimbabwe", saved.getValue().getCountry());
        assertEquals(EventCategory.CONCERT, saved.getValue().getCategory());
        assertEquals(200, saved.getValue().getTotalCapacity());
        assertEquals(200, saved.getValue().getAvailableTickets());
        assertFalse(saved.getValue().isDeleted());
    }

    @Test
    void createEvent_rejectsWhenCountryMissingFromToken() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setVenue("Venue");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.now().plusDays(10));
        req.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        req.setTotalCapacity(100);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createEvent("tenant-1", "  ", req));
        assertTrue(ex.getMessage().toLowerCase().contains("country"));
        verify(repo, never()).save(any());
    }

    @Test
    void createEvent_rejectsDuplicateForSameTenantTitleVenueDate() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        LocalDateTime when = LocalDateTime.now().plusDays(10);
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setVenue("Harare Gardens");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(when); req.setEndDateTime(when.plusHours(2)); req.setTotalCapacity(100);

        when(repo.existsByTenantIdAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                "tenant-1", "Concert", "Harare Gardens", when)).thenReturn(true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createEvent("tenant-1", "Zimbabwe", req));
        assertTrue(ex.getMessage().toLowerCase().contains("already exists"));
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_asAdmin_canEditEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId).tenantId("owner").title("Old").venue("V")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
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
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("owner-tenant")
                .title("T").venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
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
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("owner-tenant")
                .title("T").venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(1).availableTickets(1).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        service.deleteEvent("admin-user", "ROLE_SUPER_ADMIN", eventId);

        verify(repo).save(argThat(Event::isDeleted));
    }

    @Test
    void getEventById_throwsWhenMissing() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));
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
        EventService service = new EventService(repo, mapper, seats, bookings, mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("a").title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
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
    void getEventById_attachesOrganizerDetailsFromUserService() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = new EventMapper();
        SeatCategoryGateway seats = mock(SeatCategoryGateway.class);
        BookingGateway bookings = mock(BookingGateway.class);
        OrganizerGateway organizers = mock(OrganizerGateway.class);
        EventService service = new EventService(repo, mapper, seats, bookings, organizers);

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("rumbi@showtime.co.zw").title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(seats.fetchForEvent(eventId)).thenReturn(Collections.emptyList());
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        when(organizers.organizersByTenantIds(java.util.List.of("rumbi@showtime.co.zw")))
                .thenReturn(java.util.Map.of("rumbi@showtime.co.zw",
                        com.innbucks.eventservice.dto.OrganizerDTO.builder()
                                .businessName("Showtime Events")
                                .businessAddress("5 Leopold Takawira St, Bulawayo")
                                .businessEmail("hello@showtime.co.zw")
                                .build()));

        EventResponseDTO result = service.getEventById(eventId);

        assertNotNull(result.getOrganizer());
        assertEquals("Showtime Events", result.getOrganizer().getBusinessName());
        assertEquals("5 Leopold Takawira St, Bulawayo", result.getOrganizer().getBusinessAddress());
        assertEquals("hello@showtime.co.zw", result.getOrganizer().getBusinessEmail());
    }

    @Test
    void getEventById_servesEventWithoutOrganizer_whenUserServiceUnavailable() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = new EventMapper();
        SeatCategoryGateway seats = mock(SeatCategoryGateway.class);
        BookingGateway bookings = mock(BookingGateway.class);
        OrganizerGateway organizers = mock(OrganizerGateway.class);
        EventService service = new EventService(repo, mapper, seats, bookings, organizers);

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("a@b.co").title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .totalCapacity(10).availableTickets(10).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(seats.fetchForEvent(eventId)).thenReturn(Collections.emptyList());
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        // Gateway's circuit breaker fell back to an empty map.
        when(organizers.organizersByTenantIds(any())).thenReturn(Collections.emptyMap());

        EventResponseDTO result = service.getEventById(eventId);

        assertNull(result.getOrganizer());
        assertEquals("a@b.co", result.getTenantId());
    }

    @Test
    void getEventById_fallsBackToEmptySeatCategories_whenSeatServiceFails() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantId("a").title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
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

    // --- deactivate / reject / approve --------------------------------------

    private static Event baseEvent(UUID eventId, String tenantId) {
        return Event.builder()
                .eventId(eventId).tenantId(tenantId).title("T").venue("V")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
    }

    @Test
    void deactivateEvent_rejectsNonOwnerTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(baseEvent(eventId, "owner-tenant")));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.deactivateEvent("other-tenant", "ROLE_EVENT_ORGANIZER", eventId));
        assertEquals("You are not authorized to deactivate this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deactivateEvent_ownerFlipsActiveFalse() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, "tenant-1"); // active=true via @Builder.Default
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateEvent("tenant-1", "ROLE_EVENT_ORGANIZER", eventId);

        verify(repo).save(argThat(e -> !e.isActive()));
    }

    @Test
    void deactivateEvent_asAdmin_deactivatesEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(baseEvent(eventId, "owner-tenant")));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateEvent("admin-user", "ROLE_SUPER_ADMIN", eventId);

        verify(repo).save(argThat(e -> !e.isActive()));
    }

    @Test
    void activateEvent_refusesAdminRejectedEvent() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, "tenant-1");
        existing.setActive(false);
        existing.setRejected(true);
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.activateEvent("tenant-1", "ROLE_EVENT_ORGANIZER", eventId));
        assertTrue(ex.getMessage().toLowerCase().contains("rejected"));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectEvent_setsRejectedTrueAndForcesActiveFalse() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, "tenant-7"); // active=true, rejected=false
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rejectEvent(eventId);

        // Invariant: a rejected event is never left active.
        verify(repo).save(argThat(e -> e.isRejected() && !e.isActive()));
    }

    @Test
    void approveEvent_clearsRejectedFlagAndLeavesInactive() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, "tenant-7");
        existing.setActive(false);
        existing.setRejected(true);
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approveEvent(eventId);

        // Approve clears rejected but does NOT republish — organizer re-activates.
        verify(repo).save(argThat(e -> !e.isRejected() && !e.isActive()));
    }

    @Test
    void rejectEvent_throwsWhenEventMissing() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class));
        when(repo.findByEventIdAndDeletedFalse(any())).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.rejectEvent(UUID.randomUUID()));
        assertEquals("Event not found", ex.getMessage());
        verify(repo, never()).save(any());
    }
}
