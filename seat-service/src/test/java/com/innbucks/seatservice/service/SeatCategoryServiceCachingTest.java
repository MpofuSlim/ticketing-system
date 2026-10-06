package com.innbucks.seatservice.service;

import com.innbucks.seatservice.cache.ReadCacheConfig;
import com.innbucks.seatservice.cache.ReadCacheProperties;
import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.EventLookupDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.exception.ServiceUnavailableException;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The category-layout cache behind {@code GET /seat-categories?eventId=}:
 * the layout is served from cache and evicted by this service's own writes,
 * while availability and every write guard keep reading live.
 */
class SeatCategoryServiceCachingTest {

    private SeatCategoryRepository catRepo;
    private SeatRepository seatRepo;
    private BookingServiceClient bookings;
    private EventServiceClient events;
    private SeatCategoryService service;

    private final UUID eventId = UUID.randomUUID();
    private SeatCategory vip;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catRepo = mock(SeatCategoryRepository.class);
        seatRepo = mock(SeatRepository.class);
        bookings = mock(BookingServiceClient.class);
        events = mock(EventServiceClient.class);
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        when(events.fetchEvent(any(), any()))
                .thenReturn(Optional.of(EventLookupDTO.builder().totalCapacity(1_000).build()));
        service = new SeatCategoryService(catRepo, seatRepo, bookings, provider,
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties()));

        vip = category(UUID.randomUUID(), "VIP", 50, 50);
        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(vip));
        when(catRepo.findById(vip.getId())).thenReturn(Optional.of(vip));
        when(seatRepo.countSections(anyCollection())).thenAnswer(inv -> sectionsFor(inv.getArgument(0)));
        when(bookings.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.of(Map.of()));
    }

    private SeatCategory category(UUID id, String name, int total, int storedAvailable) {
        return SeatCategory.builder()
                .id(id).eventId(eventId).name(name).description("desc")
                .price(new BigDecimal("10.00")).totalSeats(total).availableSeats(storedAvailable)
                .deleted(false).build();
    }

    private static List<SeatRepository.SectionCount> sectionsFor(java.util.Collection<UUID> ids) {
        return ids.stream().<SeatRepository.SectionCount>map(id -> new SeatRepository.SectionCount() {
            public UUID getCategoryId() { return id; }
            public String getSectionLabel() { return "A"; }
            public Long getSeatCount() { return 50L; }
            public String getImageUrl() { return null; }
        }).toList();
    }

    @Test
    void aSecondListing_isServedFromTheCache_butAvailabilityIsAskedEveryTime() {
        when(bookings.fetchActiveCountsByCategories(anyCollection()))
                .thenReturn(Optional.of(Map.of(vip.getId(), 5L)))
                .thenReturn(Optional.of(Map.of(vip.getId(), 20L)));

        assertEquals(45, service.getCategoriesByEvent(eventId).get(0).getAvailableSeats());
        CreateCategoryResponseDTO second = service.getCategoriesByEvent(eventId).get(0);

        assertEquals(30, second.getAvailableSeats());
        assertEquals("VIP", second.getName());
        assertEquals(50, second.getSections().get(0).getSeatCount());
        verify(catRepo, times(1)).findByEventIdAndDeletedFalse(eventId);
        verify(seatRepo, times(1)).countSections(anyCollection());
        verify(bookings, times(2)).fetchActiveCountsByCategories(anyCollection());
    }

    @Test
    void whenBookingServiceIsDown_theStoredMirrorIsReadLive_notFromTheCache() {
        service.getCategoriesByEvent(eventId); // layout cached

        when(bookings.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.empty());
        SeatCategory current = category(vip.getId(), "VIP", 50, 12);
        when(catRepo.findAllById(anyList())).thenReturn(List.of(current));

        assertEquals(12, service.getCategoriesByEvent(eventId).get(0).getAvailableSeats());
        verify(catRepo).findAllById(List.of(vip.getId()));
    }

    @Test
    void responses_areIndependentCopies() {
        CreateCategoryResponseDTO first = service.getCategoriesByEvent(eventId).get(0);
        first.setName("defaced");
        first.getSections().get(0).setSeatCount(1);

        CreateCategoryResponseDTO second = service.getCategoriesByEvent(eventId).get(0);
        assertEquals("VIP", second.getName());
        assertEquals(50, second.getSections().get(0).getSeatCount());
    }

    @Test
    void eventsAreKeptApart() {
        UUID otherEvent = UUID.randomUUID();
        when(catRepo.findByEventIdAndDeletedFalse(otherEvent)).thenReturn(List.of());

        service.getCategoriesByEvent(eventId);
        assertTrue(service.getCategoriesByEvent(otherEvent).isEmpty());
        assertEquals(1, service.getCategoriesByEvent(eventId).size());
        verify(catRepo, times(1)).findByEventIdAndDeletedFalse(eventId);
        verify(catRepo, times(1)).findByEventIdAndDeletedFalse(otherEvent);
    }

    @Test
    void create_evictsThatEventsLayout() {
        service.getCategoriesByEvent(eventId);

        SectionSeatConfigDTO section = new SectionSeatConfigDTO();
        section.setSection("B");
        section.setSeatCount(10);
        CreateCategoryRequestDTO req = new CreateCategoryRequestDTO();
        req.setEventId(eventId);
        req.setName("GA");
        req.setPrice(new BigDecimal("5.00"));
        req.setSections(List.of(section));
        service.createCategory(req);

        SeatCategory ga = category(UUID.randomUUID(), "GA", 10, 10);
        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(vip, ga));
        assertEquals(2, service.getCategoriesByEvent(eventId).size());
    }

    @Test
    void update_evictsThatEventsLayout() {
        service.getCategoriesByEvent(eventId);

        UpdateCategoryRequestDTO req = new UpdateCategoryRequestDTO();
        req.setName("Gold");
        req.setPrice(new BigDecimal("15.00"));
        service.updateCategory(vip.getId(), req);

        CreateCategoryResponseDTO after = service.getCategoriesByEvent(eventId).get(0);
        assertEquals("Gold", after.getName());
        assertEquals(new BigDecimal("15.00"), after.getPrice());
    }

    @Test
    void delete_evictsThatEventsLayout() {
        service.getCategoriesByEvent(eventId);

        service.deleteCategory(vip.getId());

        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of());
        assertTrue(service.getCategoriesByEvent(eventId).isEmpty());
    }

    @Test
    void theCapacityGuard_sumsLiveRows_notTheCachedLayout() {
        when(events.fetchEvent(any(), any()))
                .thenReturn(Optional.of(EventLookupDTO.builder().totalCapacity(120).build()));
        service.getCategoriesByEvent(eventId); // caches one 50-seat category

        // Meanwhile (another replica) a second category took the allocation to 100.
        when(catRepo.findByEventIdAndDeletedFalse(eventId))
                .thenReturn(List.of(vip, category(UUID.randomUUID(), "GA", 50, 50)));
        SectionSeatConfigDTO section = new SectionSeatConfigDTO();
        section.setSection("C");
        section.setSeatCount(30);
        CreateCategoryRequestDTO req = new CreateCategoryRequestDTO();
        req.setEventId(eventId);
        req.setName("Late");
        req.setPrice(new BigDecimal("5.00"));
        req.setSections(List.of(section));

        assertThrows(ConflictException.class, () -> service.createCategory(req));
    }

    @Test
    void theDeleteGuard_asksBookingServiceEveryTime_andFailsClosed() {
        service.getCategoriesByEvent(eventId);
        when(bookings.fetchActiveCountsByCategories(anyCollection())).thenReturn(Optional.empty());

        assertThrows(ServiceUnavailableException.class, () -> service.deleteCategory(vip.getId()));
        assertThrows(ServiceUnavailableException.class, () -> service.deleteCategory(vip.getId()));
        verify(bookings, times(3)).fetchActiveCountsByCategories(anyCollection());
        verify(catRepo, never()).save(any());
    }

    @Test
    void theBookingPathsCategoryLookup_isNeverCached() {
        service.getCategoryCapacity(vip.getId());
        vip.setPrice(new BigDecimal("99.00"));
        assertEquals(new BigDecimal("99.00"), service.getCategoryCapacity(vip.getId()).getPrice());
        verify(catRepo, times(2)).findById(vip.getId());
    }

    @Test
    void withoutACacheManager_everyListingLoads() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        SeatCategoryService uncached = new SeatCategoryService(catRepo, seatRepo, bookings, provider);
        uncached.getCategoriesByEvent(eventId);
        uncached.getCategoriesByEvent(eventId);
        verify(catRepo, times(2)).findByEventIdAndDeletedFalse(eventId);
    }
}
