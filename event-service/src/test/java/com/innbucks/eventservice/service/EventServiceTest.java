package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
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
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventServiceTest {

    // Stable per-organizer UUIDs minted once per class so ownership /
    // cross-organizer assertions stay legible after the email-as-tenantId
    // pattern was removed in V7.
    private static final UUID OWNER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID TENANT_1 = UUID.randomUUID();
    private static final UUID TENANT_7 = UUID.randomUUID();
    private static final UUID TENANT_9 = UUID.randomUUID();
    private static final UUID ADMIN_USER = UUID.randomUUID();
    private static final UUID ORGANIZER_A = UUID.randomUUID();
    private static final UUID ORGANIZER_RUMBI = UUID.randomUUID();
    private static final UUID ORGANIZER_AB = UUID.randomUUID();

    @Test
    void updateEvent_rejectsWhenTenantDoesNotMatch() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantUserUuid(OWNER_TENANT)
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
                () -> service.updateEvent(OTHER_TENANT, eventId, new UpdateEventRequestDTO()));

        assertEquals("You are not authorized to update this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_adjustsAvailableTicketsByCapacityDiff() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId)
                .tenantUserUuid(TENANT_1)
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
        req.setTotalCapacity(120);

        service.updateEvent(TENANT_1, eventId, req);

        ArgumentCaptor<Event> savedCaptor = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(savedCaptor.capture());

        Event saved = savedCaptor.getValue();
        assertEquals(120, saved.getTotalCapacity());
        assertEquals(80, saved.getAvailableTickets());
    }

    @Test
    void createEvent_initializesAvailableTicketsToTotalCapacity() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setDescription("desc"); req.setVenue("Venue");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.now().plusDays(10));
        req.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        req.setTotalCapacity(200);
        when(repo.existsByTenantUserUuidAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                any(), any(), any(), any())).thenReturn(false);
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createEvent(TENANT_9, "Zimbabwe", req);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(repo).save(saved.capture());
        assertEquals(TENANT_9, saved.getValue().getTenantUserUuid());
        assertEquals("Zimbabwe", saved.getValue().getCountry());
        assertEquals(EventCategory.CONCERT, saved.getValue().getCategory());
        assertEquals(200, saved.getValue().getTotalCapacity());
        assertEquals(200, saved.getValue().getAvailableTickets());
        assertFalse(saved.getValue().isDeleted());
    }

    @Test
    void createEvent_rejectsWhenCountryMissingFromToken() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setVenue("Venue");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.now().plusDays(10));
        req.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        req.setTotalCapacity(100);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createEvent(TENANT_1, "  ", req));
        assertTrue(ex.getMessage().toLowerCase().contains("country"));
        verify(repo, never()).save(any());
    }

    @Test
    void createEvent_rejectsDuplicateForSameTenantTitleVenueDate() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        LocalDateTime when = LocalDateTime.now().plusDays(10);
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Concert"); req.setVenue("Harare Gardens");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(when); req.setEndDateTime(when.plusHours(2)); req.setTotalCapacity(100);

        when(repo.existsByTenantUserUuidAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                TENANT_1, "Concert", "Harare Gardens", when)).thenReturn(true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createEvent(TENANT_1, "Zimbabwe", req));
        assertTrue(ex.getMessage().toLowerCase().contains("already exists"));
        verify(repo, never()).save(any());
    }

    @Test
    void updateEvent_asAdmin_canEditEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder()
                .eventId(eventId).tenantUserUuid(OWNER_TENANT).title("Old").venue("V")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setTitle("New Title");

        service.updateEvent(ADMIN_USER, "ROLE_SUPER_ADMIN", eventId, req);

        verify(repo).save(argThat(e -> "New Title".equals(e.getTitle())));
    }

    @Test
    void deleteEvent_rejectsNonOwnerTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(OWNER_TENANT)
                .title("T").venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(1).availableTickets(1).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.deleteEvent(OTHER_TENANT, "ROLE_EVENT_ORGANIZER", eventId));
        assertEquals("You are not authorized to delete this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteEvent_asAdmin_softDeletesEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(OWNER_TENANT)
                .title("T").venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(1).availableTickets(1).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        service.deleteEvent(ADMIN_USER, "ROLE_SUPER_ADMIN", eventId);

        verify(repo).save(argThat(Event::isDeleted));
    }

    @Test
    void getEventById_throwsWhenMissing() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));
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
        EventService service = new EventService(repo, mapper, seats, bookings, mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(ORGANIZER_A).title("T")
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
        EventService service = new EventService(repo, mapper, seats, bookings, organizers, mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(ORGANIZER_RUMBI).title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(seats.fetchForEvent(eventId)).thenReturn(Collections.emptyList());
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        when(organizers.organizersByUserUuids(java.util.List.of(ORGANIZER_RUMBI)))
                .thenReturn(java.util.Map.of(ORGANIZER_RUMBI,
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
        EventService service = new EventService(repo, mapper, seats, bookings, organizers, mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(ORGANIZER_AB).title("T")
                .venue("V").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(1))
                .endDateTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .totalCapacity(10).availableTickets(10).deleted(false).build();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(seats.fetchForEvent(eventId)).thenReturn(Collections.emptyList());
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        // Gateway's circuit breaker fell back to an empty map.
        when(organizers.organizersByUserUuids(any())).thenReturn(Collections.emptyMap());

        EventResponseDTO result = service.getEventById(eventId);

        assertNull(result.getOrganizer());
        assertEquals(ORGANIZER_AB, result.getTenantUserUuid());
    }

    @Test
    void getEventById_fallsBackToEmptySeatCategories_whenSeatServiceFails() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        SeatCategoryGateway gateway = mock(SeatCategoryGateway.class);
        EventService service = new EventService(repo, mapper, gateway, mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = Event.builder().eventId(eventId).tenantUserUuid(ORGANIZER_A).title("T")
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

    private static Event baseEvent(UUID eventId, UUID tenantUserUuid) {
        return Event.builder()
                .eventId(eventId).tenantUserUuid(tenantUserUuid).title("T").venue("V")
                .country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).build();
    }

    @Test
    void deactivateEvent_rejectsNonOwnerTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(baseEvent(eventId, OWNER_TENANT)));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.deactivateEvent(OTHER_TENANT, "ROLE_EVENT_ORGANIZER", eventId));
        assertEquals("You are not authorized to deactivate this event", ex.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deactivateEvent_ownerFlipsActiveFalse() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, TENANT_1); // active=true via @Builder.Default
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateEvent(TENANT_1, "ROLE_EVENT_ORGANIZER", eventId);

        verify(repo).save(argThat(e -> !e.isActive()));
    }

    @Test
    void deactivateEvent_asAdmin_deactivatesEventOwnedByAnotherTenant() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(baseEvent(eventId, OWNER_TENANT)));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateEvent(ADMIN_USER, "ROLE_SUPER_ADMIN", eventId);

        verify(repo).save(argThat(e -> !e.isActive()));
    }

    @Test
    void activateEvent_refusesAdminRejectedEvent() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, TENANT_1);
        existing.setActive(false);
        existing.setRejected(true);
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.activateEvent(TENANT_1, "ROLE_EVENT_ORGANIZER", eventId));
        assertTrue(ex.getMessage().toLowerCase().contains("rejected"));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectEvent_setsRejectedTrueAndForcesActiveFalse() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, TENANT_7); // active=true, rejected=false
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existing));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rejectEvent(eventId);

        // Invariant: a rejected event is never left active.
        verify(repo).save(argThat(e -> e.isRejected() && !e.isActive()));
    }

    @Test
    void approveEvent_clearsRejectedFlagAndLeavesInactive() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        UUID eventId = UUID.randomUUID();
        Event existing = baseEvent(eventId, TENANT_7);
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
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));
        when(repo.findByEventIdAndDeletedFalse(any())).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.rejectEvent(UUID.randomUUID()));
        assertEquals("Event not found", ex.getMessage());
        verify(repo, never()).save(any());
    }

    private Event existingEventFor(UUID eventId) {
        return Event.builder()
                .eventId(eventId).tenantUserUuid(TENANT_1).title("Jazz Night")
                .venue("Old Arena").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(5))
                .endDateTime(LocalDateTime.now().plusDays(5).plusHours(2))
                .totalCapacity(100).availableTickets(60).deleted(false).build();
    }

    @Test
    void updateEvent_venueChange_notifiesBookingService() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        BookingNotificationGateway notify = mock(BookingNotificationGateway.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), notify);

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existingEventFor(eventId)));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setVenue("New Stadium");

        service.updateEvent(TENANT_1, eventId, req);

        // venue changed → notify UPDATED with the new venue and no time change.
        verify(notify).notifyEventChange(eq(eventId), eq("UPDATED"), eq("Jazz Night"),
                isNull(), eq("New Stadium"));
    }

    @Test
    void updateEvent_descriptionOnlyChange_doesNotNotify() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        BookingNotificationGateway notify = mock(BookingNotificationGateway.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), notify);

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existingEventFor(eventId)));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setDescription("Now with an opening act");

        service.updateEvent(TENANT_1, eventId, req);

        // No customer-affecting field changed → no attendee notification.
        verifyNoInteractions(notify);
    }

    @Test
    void updateEvent_sameVenueValue_doesNotNotify() {
        EventRepository repo = mock(EventRepository.class);
        EventMapper mapper = mock(EventMapper.class);
        BookingNotificationGateway notify = mock(BookingNotificationGateway.class);
        EventService service = new EventService(repo, mapper, mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), notify);

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existingEventFor(eventId)));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        // Re-sends the SAME venue — a no-op change must not spam attendees.
        UpdateEventRequestDTO req = new UpdateEventRequestDTO();
        req.setVenue("Old Arena");

        service.updateEvent(TENANT_1, eventId, req);

        verifyNoInteractions(notify);
    }

    @Test
    void deleteEvent_notifiesCancellation() {
        EventRepository repo = mock(EventRepository.class);
        BookingNotificationGateway notify = mock(BookingNotificationGateway.class);
        EventService service = new EventService(repo, mock(EventMapper.class), mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), notify);

        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndDeletedFalse(eventId)).thenReturn(Optional.of(existingEventFor(eventId)));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteEvent(TENANT_1, eventId);

        verify(notify).notifyEventChange(eq(eventId), eq("CANCELLED"), eq("Jazz Night"),
                isNull(), isNull());
    }

    // ---------------------------------------------------------------------
    // Team-member assigned-events scoping (deny-by-default). Mirrors the
    // empty short-circuit + delegation contract of getMyAssignedEvents for
    // the new active/inactive variants behind GET /events/active and
    // GET /events/inactive. These are the service-layer half of the
    // broken-access-control fix: a TEAM_MEMBER must only ever see the events
    // their organizer assigned to them, never the organizer-wide or public set.
    // ---------------------------------------------------------------------

    private static Event activeAssigned(UUID eventId, UUID organizerUuid) {
        return Event.builder()
                .eventId(eventId).tenantUserUuid(organizerUuid).title("Assigned Active")
                .venue("Arena").country("Zimbabwe").category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now().plusDays(3))
                .endDateTime(LocalDateTime.now().plusDays(3).plusHours(2))
                .totalCapacity(100).availableTickets(100).deleted(false).active(true).build();
    }

    @Test
    void getMyAssignedActiveEvents_emptyAssignments_returnsEmptyPage_withoutHittingDb() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        // Deny-by-default: no assignments → empty page, and NO repository call
        // (an empty IN(...) is both wrong semantically and illegal in JPA).
        Page<EventResponseDTO> result = service.getMyAssignedActiveEvents(
                ORGANIZER_A, java.util.List.of(), null, null, null, null, null, 0, 10, "startDateTime");

        assertTrue(result.isEmpty());
        verify(repo, never()).findByTenantUserUuidActiveOnlyAndEventIdIn(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).findByTenantUserUuidActiveOnlyByCategoryAndEventIdIn(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getMyAssignedActiveEvents_nullAssignments_returnsEmptyPage() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        Page<EventResponseDTO> result = service.getMyAssignedActiveEvents(
                ORGANIZER_A, null, null, null, null, null, null, 0, 10, "startDateTime");

        assertTrue(result.isEmpty());
        verifyNoInteractions(repo);
    }

    @Test
    void getMyAssignedActiveEvents_noCategory_delegatesToEventIdInQuery_scopedToOrganizerAndAssignedIds() {
        EventRepository repo = mock(EventRepository.class);
        BookingGateway bookings = mock(BookingGateway.class);
        OrganizerGateway organizers = mock(OrganizerGateway.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                bookings, organizers, mock(BookingNotificationGateway.class));

        UUID assignedId = UUID.randomUUID();
        java.util.List<UUID> assigned = java.util.List.of(assignedId);
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        when(organizers.organizersByUserUuids(any())).thenReturn(Collections.emptyMap());
        when(repo.findByTenantUserUuidActiveOnlyAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(activeAssigned(assignedId, ORGANIZER_A))));

        Page<EventResponseDTO> result = service.getMyAssignedActiveEvents(
                ORGANIZER_A, assigned, null, null, null, null, null, 0, 10, "startDateTime");

        // Only the assigned event comes back, and it routed through the
        // organizer+eventId-scoped query (not the unfiltered active query).
        assertEquals(1, result.getNumberOfElements());
        assertEquals(assignedId, result.getContent().get(0).getEventId());
        verify(repo).findByTenantUserUuidActiveOnlyAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), any(), any());
        verify(repo, never()).findByTenantUserUuidActiveOnly(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getMyAssignedActiveEvents_withCategory_delegatesToCategoryEventIdInQuery() {
        EventRepository repo = mock(EventRepository.class);
        BookingGateway bookings = mock(BookingGateway.class);
        OrganizerGateway organizers = mock(OrganizerGateway.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                bookings, organizers, mock(BookingNotificationGateway.class));

        UUID assignedId = UUID.randomUUID();
        java.util.List<UUID> assigned = java.util.List.of(assignedId);
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        when(organizers.organizersByUserUuids(any())).thenReturn(Collections.emptyMap());
        when(repo.findByTenantUserUuidActiveOnlyByCategoryAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), eq(EventCategory.CONCERT), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(activeAssigned(assignedId, ORGANIZER_A))));

        Page<EventResponseDTO> result = service.getMyAssignedActiveEvents(
                ORGANIZER_A, assigned, null, null, null, null, EventCategory.CONCERT, 0, 10, "startDateTime");

        assertEquals(1, result.getNumberOfElements());
        verify(repo).findByTenantUserUuidActiveOnlyByCategoryAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), eq(EventCategory.CONCERT), any(), any());
        verify(repo, never()).findByTenantUserUuidActiveOnlyAndEventIdIn(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getMyAssignedInactiveEvents_emptyAssignments_returnsEmptyPage_withoutHittingDb() {
        EventRepository repo = mock(EventRepository.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                mock(BookingGateway.class), mock(OrganizerGateway.class), mock(BookingNotificationGateway.class));

        Page<EventResponseDTO> result = service.getMyAssignedInactiveEvents(
                ORGANIZER_A, java.util.List.of(), null, null, null, null, null, 0, 10, "startDateTime");

        assertTrue(result.isEmpty());
        verify(repo, never()).findByTenantUserUuidInactiveOnlyAndEventIdIn(
                any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).findByTenantUserUuidInactiveOnlyByCategoryAndEventIdIn(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getMyAssignedInactiveEvents_noCategory_delegatesToInactiveEventIdInQuery() {
        EventRepository repo = mock(EventRepository.class);
        BookingGateway bookings = mock(BookingGateway.class);
        OrganizerGateway organizers = mock(OrganizerGateway.class);
        EventService service = new EventService(repo, new EventMapper(), mock(SeatCategoryGateway.class),
                bookings, organizers, mock(BookingNotificationGateway.class));

        UUID assignedId = UUID.randomUUID();
        java.util.List<UUID> assigned = java.util.List.of(assignedId);
        Event inactive = activeAssigned(assignedId, ORGANIZER_A);
        inactive.setActive(false);
        when(bookings.activeCountsByEventIds(any())).thenReturn(Collections.emptyMap());
        when(organizers.organizersByUserUuids(any())).thenReturn(Collections.emptyMap());
        when(repo.findByTenantUserUuidInactiveOnlyAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(inactive)));

        Page<EventResponseDTO> result = service.getMyAssignedInactiveEvents(
                ORGANIZER_A, assigned, null, null, null, null, null, 0, 10, "startDateTime");

        assertEquals(1, result.getNumberOfElements());
        assertEquals(assignedId, result.getContent().get(0).getEventId());
        verify(repo).findByTenantUserUuidInactiveOnlyAndEventIdIn(
                eq(ORGANIZER_A), eq(assigned), any(), any(), any(), any(), any());
        verify(repo, never()).findByTenantUserUuidInactiveOnly(any(), any(), any(), any(), any(), any());
    }
}
