package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.cache.ReadCacheConfig;
import com.innbucks.eventservice.cache.ReadCacheProperties;
import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The public event-list cache, driven through {@link EventService} with the
 * real Caffeine manager {@link ReadCacheConfig} builds: what is served from
 * cache, what every write clears, what keys keep apart, and — just as
 * load-bearing — what stays live on every call (availability, the by-id read
 * that decides draft visibility, the organizer branch).
 */
class EventServiceCachingTest {

    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private EventRepository repo;
    private BookingGateway bookings;
    private OrganizerGateway organizers;
    private SeatCategoryGateway seats;
    private EventService service;
    private Event event;

    @BeforeEach
    void setUp() {
        repo = mock(EventRepository.class);
        bookings = mock(BookingGateway.class);
        organizers = mock(OrganizerGateway.class);
        seats = mock(SeatCategoryGateway.class);
        EventMapper mapper = new EventMapper();
        CacheManager manager = new ReadCacheConfig().readCacheManager(new ReadCacheProperties());
        service = new EventService(repo, new MarketTimeZone("ZW"), mapper, seats, bookings, organizers,
                mock(BookingNotificationGateway.class), mock(OrganizerNotificationGateway.class),
                new PublicEventCatalog(manager, mapper));

        event = Event.builder()
                .eventId(UUID.randomUUID())
                .tenantUserUuid(ORGANIZER)
                .title("Summer Concert")
                .venue("Harare Gardens")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(10))
                .endDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(10).plusHours(3))
                .totalCapacity(100)
                .availableTickets(100)
                .deleted(false)
                .build();

        when(repo.findAllActiveOnly(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(5)));
        when(repo.findAllActiveOnlyByCategory(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(6)));
        when(repo.searchByKeyword(any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(2)));
        when(repo.findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(2)));
        when(repo.findByTenantUserUuid(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(4)));
        when(repo.findByTenantUserUuidActiveOnly(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> pageOf(inv.getArgument(6)));
        when(repo.findByEventIdAndDeletedFalse(event.getEventId())).thenReturn(Optional.of(event));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizers.organizersByUserUuids(anyCollection()))
                .thenReturn(Map.of(ORGANIZER, OrganizerDTO.builder().businessName("Showtime").build()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Page<Event> pageOf(Pageable pageable) {
        return new PageImpl<>(List.of(event), pageable, 1);
    }

    private Page<EventResponseDTO> publicActive() {
        return service.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "startDateTime");
    }

    @Test
    void aSecondIdenticalPublicRead_isServedFromCache() {
        Page<EventResponseDTO> first = publicActive();
        Page<EventResponseDTO> second = publicActive();

        verify(repo, times(1)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
        assertEquals(1, second.getTotalElements());
        assertEquals("Summer Concert", second.getContent().get(0).getTitle());
        assertEquals(first.getContent().get(0).getEventId(), second.getContent().get(0).getEventId());
        // Same public shape as an uncached read: organizer details attached,
        // the organizer's internal id stripped.
        assertNull(second.getContent().get(0).getTenantUserUuid());
        assertEquals("Showtime", second.getContent().get(0).getOrganizer().getBusinessName());
    }

    @Test
    void getAllActiveEvents_andTheUnfilteredActiveListing_shareOneEntry() {
        service.getAllActiveEvents(null, null, null, 0, 10, "startDateTime");
        publicActive();
        service.getAllActiveEvents(null, null, null, 0, 10, null); // blank sort = the default field

        verify(repo, times(1)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void searchAndByCountry_areCachedToo() {
        service.searchEvents("  Harare ", 0, 10, "startDateTime");
        service.searchEvents("Harare", 0, 10, "startDateTime"); // trimmed to the same keyword
        service.getEventsByCountry("Zimbabwe", 0, 10);
        Page<EventResponseDTO> again = service.getEventsByCountry("Zimbabwe", 0, 10);

        verify(repo, times(1)).searchByKeyword(eq("Harare"), any(), any());
        verify(repo, times(1))
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                        any(), any(), any());
        // eventNo is per-response numbering, applied after the cache.
        assertEquals(1, again.getContent().get(0).getEventNo());
    }

    @Test
    void everyQueryInput_isPartOfTheKey() {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(7).atStartOfDay();

        Runnable[] distinct = {
                () -> service.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "startDateTime"),
                () -> service.getActiveOnlyEvents(null, null, null, null, null, 1, 10, "startDateTime"),   // page
                () -> service.getActiveOnlyEvents(null, null, null, null, null, 0, 20, "startDateTime"),   // size
                () -> service.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "title"),           // sort
                () -> service.getActiveOnlyEvents(from, null, null, null, null, 0, 10, "startDateTime"),   // from
                () -> service.getActiveOnlyEvents(from, to, null, null, null, 0, 10, "startDateTime"),     // to
                () -> service.getActiveOnlyEvents(null, null, "Gardens", null, null, 0, 10, "startDateTime"), // venue
                () -> service.getActiveOnlyEvents(null, null, null, "Zimbabwe", null, 0, 10, "startDateTime"), // country
                () -> service.getActiveOnlyEvents(null, null, null, null, EventCategory.CONCERT, 0, 10, "startDateTime"), // category
                () -> service.getActiveOnlyEvents(null, null, null, null, EventCategory.SPORT, 0, 10, "startDateTime"),
        };
        for (Runnable r : distinct) r.run();
        for (Runnable r : distinct) r.run(); // all hits now

        verify(repo, times(8)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
        verify(repo, times(2)).findAllActiveOnlyByCategory(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSearchAndAVenueFilterWithTheSameText_neverShareAnEntry() {
        service.searchEvents("Harare", 0, 10, "startDateTime");
        service.getActiveOnlyEvents(null, null, "Harare", null, null, 0, 10, "startDateTime");
        service.getEventsByCountry("Harare", 0, 10);

        verify(repo, times(1)).searchByKeyword(any(), any(), any());
        verify(repo, times(1)).findAllActiveOnly(any(), any(), eq("Harare"), isNull(), any(), any());
        verify(repo, times(1))
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                        eq("Harare"), any(), any());
    }

    @Test
    void availability_isLiveOnEveryRead_evenWhenThePageIsCached() {
        when(bookings.activeCountsByEventIds(anyCollection()))
                .thenReturn(Map.of(event.getEventId(), 10L))
                .thenReturn(Map.of(event.getEventId(), 30L));

        assertEquals(90, publicActive().getContent().get(0).getAvailableTickets());
        assertEquals(70, publicActive().getContent().get(0).getAvailableTickets());

        verify(repo, times(1)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
        verify(bookings, times(2)).activeCountsByEventIds(anyCollection());
    }

    @Test
    void organizerDetails_areResolvedOnEveryRead() {
        publicActive();
        publicActive();
        // The gateway keeps its own per-organizer cache; the service asks it each time.
        verify(organizers, times(2)).organizersByUserUuids(anyCollection());
    }

    @Test
    void aCallerMutatingItsResponse_cannotChangeWhatTheNextCallerIsServed() {
        EventResponseDTO first = publicActive().getContent().get(0);
        first.setTitle("defaced");
        first.setTenantUserUuid(UUID.randomUUID());
        first.getLocation(); // null here; the record holds no shared mutable parts

        EventResponseDTO second = publicActive().getContent().get(0);
        assertEquals("Summer Concert", second.getTitle());
        assertNull(second.getTenantUserUuid());
        assertNotSame(first, second);
    }

    @Test
    void anInvalidSortField_isRefusedBeforeTheCache_andNothingIsLoaded() {
        assertThrows(BadRequestException.class,
                () -> service.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "passwordHash"));
        assertThrows(BadRequestException.class,
                () -> service.searchEvents("x", 0, 10, "passwordHash"));
        verifyNoInteractions(bookings);
        verify(repo, never()).findAllActiveOnly(any(), any(), any(), any(), any(), any());
        verify(repo, never()).searchByKeyword(any(), any(), any());
    }

    @Test
    void theOrganizerBranch_isNeverCached() {
        service.getMyEvents(ORGANIZER, null, null, null, 0, 10, "startDateTime");
        service.getMyEvents(ORGANIZER, null, null, null, 0, 10, "startDateTime");
        service.getMyActiveEvents(ORGANIZER, null, null, null, null, null, 0, 10, "startDateTime");
        service.getMyActiveEvents(ORGANIZER, null, null, null, null, null, 0, 10, "startDateTime");

        verify(repo, times(2)).findByTenantUserUuid(any(), any(), any(), any(), any());
        verify(repo, times(2)).findByTenantUserUuidActiveOnly(any(), any(), any(), any(), any(), any(), any());
        // ...and an organizer's own listing keeps its tenantUserUuid.
        assertEquals(ORGANIZER, service.getMyEvents(ORGANIZER, null, null, null, 0, 10, "startDateTime")
                .getContent().get(0).getTenantUserUuid());
        verify(repo, never()).findAllActiveOnly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theByIdRead_isLiveOnEveryCall_becauseItDecidesDraftVisibility() {
        service.getEventById(event.getEventId());
        service.getEventById(event.getEventId());
        verify(repo, times(2)).findByEventIdAndDeletedFalse(event.getEventId());

        // Unpublished between two reads: the very next anonymous read is a 404.
        event.setActive(false);
        assertThrows(com.innbucks.eventservice.exception.NotFoundException.class,
                () -> service.getEventById(event.getEventId()));
    }

    @Test
    void theByIdRead_servesThePublicTheCachedLayout_andTheOwnerTheLiveOne() {
        service.getEventById(event.getEventId());
        verify(seats).fetchForEvent(event.getEventId());
        verify(seats, never()).fetchForEventUncached(any());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        service.getEventById(event.getEventId());
        verify(seats).fetchForEventUncached(event.getEventId());
        verify(seats, times(1)).fetchForEvent(any());
    }

    static Stream<Arguments> writes() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        return Stream.of(
                Arguments.of("update", (Consumer<EventServiceCachingTest>) t ->
                        t.service.updateEvent(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId(), new UpdateEventRequestDTO())),
                Arguments.of("activate", (Consumer<EventServiceCachingTest>) t ->
                        t.service.activateEvent(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId())),
                Arguments.of("deactivate", (Consumer<EventServiceCachingTest>) t ->
                        t.service.deactivateEvent(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId())),
                Arguments.of("reject", (Consumer<EventServiceCachingTest>) t ->
                        t.service.rejectEvent(t.event.getEventId())),
                Arguments.of("approve", (Consumer<EventServiceCachingTest>) t ->
                        t.service.approveEvent(t.event.getEventId())),
                Arguments.of("replace banner", (Consumer<EventServiceCachingTest>) t ->
                        t.service.replaceEventBanner(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId(),
                                new MockMultipartFile("banner", "b.png", "image/png", png))),
                Arguments.of("delete banner", (Consumer<EventServiceCachingTest>) t ->
                        t.service.deleteEventBanner(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId())),
                Arguments.of("delete", (Consumer<EventServiceCachingTest>) t ->
                        t.service.deleteEvent(ADMIN, "ROLE_SUPER_ADMIN", t.event.getEventId()))
        );
    }

    @ParameterizedTest(name = "{0} clears the public list cache")
    @MethodSource("writes")
    void everyEventWrite_clearsThePublicListCache(String name, Consumer<EventServiceCachingTest> write) {
        publicActive();
        service.searchEvents("Harare", 0, 10, "startDateTime");
        service.getEventsByCountry("Zimbabwe", 0, 10);

        write.accept(this);

        publicActive();
        service.searchEvents("Harare", 0, 10, "startDateTime");
        service.getEventsByCountry("Zimbabwe", 0, 10);
        verify(repo, times(2)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
        verify(repo, times(2)).searchByKeyword(any(), any(), any());
        verify(repo, times(2))
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                        any(), any(), any());
    }

    @Test
    void availabilityWrites_doNotClearTheCache() {
        when(repo.decrementAvailableTickets(any(), anyInt())).thenReturn(1);
        when(repo.releaseAvailableTickets(any(), anyInt())).thenReturn(1);
        publicActive();

        service.consumeAvailability(event.getEventId(), 1);
        service.releaseAvailability(event.getEventId(), 1);

        publicActive();
        verify(repo, times(1)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theCapacityGuard_readsTheAllocationLive_everyTime() {
        when(seats.fetchAllocatedSeats(event.getEventId())).thenReturn(Optional.of(10L));
        UpdateEventRequestDTO raise = new UpdateEventRequestDTO();
        raise.setTotalCapacity(120);

        service.updateEvent(ADMIN, "ROLE_SUPER_ADMIN", event.getEventId(), raise);
        service.updateEvent(ADMIN, "ROLE_SUPER_ADMIN", event.getEventId(), raise);

        verify(seats, times(2)).fetchAllocatedSeats(event.getEventId());
    }

    @Test
    void withoutACatalog_everyReadLoads() {
        EventMapper mapper = new EventMapper();
        EventService uncached = new EventService(repo, new MarketTimeZone("ZW"), mapper, seats, bookings,
                organizers, mock(BookingNotificationGateway.class), mock(OrganizerNotificationGateway.class));
        uncached.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "startDateTime");
        uncached.getActiveOnlyEvents(null, null, null, null, null, 0, 10, "startDateTime");
        verify(repo, times(2)).findAllActiveOnly(any(), any(), any(), any(), any(), any());
    }
}
