package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SeatCategoryServiceTest {

    // The service constructor takes (categoryRepo, seatRepo, bookingServiceClient,
    // ObjectProvider<EventServiceClient>). Most tests don't exercise the booking
    // client, so this helper supplies a default mock; the live-availability tests
    // pass their own stubbed client.
    @SuppressWarnings("unchecked")
    private SeatCategoryService service(SeatCategoryRepository catRepo,
                                        SeatRepository seatRepo,
                                        BookingServiceClient bookingClient) {
        return new SeatCategoryService(catRepo, seatRepo, bookingClient,
                (ObjectProvider<EventServiceClient>) mock(ObjectProvider.class));
    }

    private SeatCategoryService service(SeatCategoryRepository catRepo, SeatRepository seatRepo) {
        return service(catRepo, seatRepo, mock(BookingServiceClient.class));
    }

    private SectionSeatConfigDTO section(String label, int count) {
        SectionSeatConfigDTO s = new SectionSeatConfigDTO();
        s.setSection(label);
        s.setSeatCount(count);
        return s;
    }

    private CreateCategoryRequestDTO request(UUID eventId, String name, List<SectionSeatConfigDTO> sections) {
        CreateCategoryRequestDTO req = new CreateCategoryRequestDTO();
        req.setEventId(eventId);
        req.setName(name);
        req.setPrice(new BigDecimal("20.00"));
        req.setSections(sections);
        return req;
    }

    private SeatCategory category(UUID id, UUID eventId, int total, int storedAvailable) {
        return SeatCategory.builder()
                .id(id)
                .eventId(eventId)
                .name("Cat-" + id)
                .price(new BigDecimal("10.00"))
                .totalSeats(total)
                .availableSeats(storedAvailable)
                .deleted(false)
                .build();
    }

    @Test
    void createCategory_autoGeneratesSeatsPerSection_andInitializesAvailability() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);
        UUID eventId = UUID.randomUUID();

        CreateCategoryResponseDTO resp = service.createCategory(
                request(eventId, "VIP", List.of(section("A", 3), section("B", 2))));

        ArgumentCaptor<SeatCategory> savedCategory = ArgumentCaptor.forClass(SeatCategory.class);
        verify(catRepo).save(savedCategory.capture());
        assertEquals(5, savedCategory.getValue().getTotalSeats());
        assertEquals(5, savedCategory.getValue().getAvailableSeats());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepo).saveAll(seatsCaptor.capture());
        List<Seat> saved = seatsCaptor.getValue();
        assertEquals(5, saved.size());
        assertEquals(3, saved.stream().filter(s -> s.getSectionLabel().equals("A")).count());
        assertEquals(2, saved.stream().filter(s -> s.getSectionLabel().equals("B")).count());
        assertTrue(saved.stream().allMatch(s -> s.getStatus() == Seat.SeatStatus.AVAILABLE));

        assertEquals("VIP", resp.getName());
        assertEquals(2, resp.getSections().size());
        // Just created — no bookings yet, so the response reports full capacity.
        assertEquals(5, resp.getAvailableSeats());
    }

    @Test
    void createCategory_rejectsDuplicateCategoryNameForSameEvent() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);
        UUID eventId = UUID.randomUUID();
        when(catRepo.existsByEventIdAndNameAndDeletedFalse(eventId, "VIP")).thenReturn(true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(request(eventId, "VIP", List.of(section("A", 1)))));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(catRepo, never()).save(any());
        verify(seatRepo, never()).saveAll(anyList());
    }

    @Test
    void createCategory_rejectsDuplicateSectionsCaseInsensitively() {
        SeatCategoryService service = service(
                mock(SeatCategoryRepository.class), mock(SeatRepository.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(request(UUID.randomUUID(), "VIP",
                        List.of(section("A", 3), section(" a ", 2)))));
        assertTrue(ex.getMessage().contains("Duplicate section"));
    }

    @Test
    void createCategory_normalizesSectionLabelToUppercase() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);

        service.createCategory(request(UUID.randomUUID(), "VIP",
                List.of(section(" a ", 2))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepo).saveAll(seatsCaptor.capture());
        assertTrue(seatsCaptor.getValue().stream()
                .allMatch(s -> s.getSectionLabel().equals("A")));
    }

    @Test
    void createCategory_rejectsTotalSeatsAboveAggregateCap() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);

        // 6 sections × 100,000 seats = 600,000 — within per-section @Max (100k)
        // and sections @Size (100), but above the service's 500k aggregate cap.
        // We can't trigger @Max here because bean validation is enforced by
        // the @Valid binding at the controller, not by direct service calls.
        List<SectionSeatConfigDTO> sections = List.of(
                section("A", 100_000), section("B", 100_000), section("C", 100_000),
                section("D", 100_000), section("E", 100_000), section("F", 100_000));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(request(UUID.randomUUID(), "Mega", sections)));

        assertTrue(ex.getMessage().contains("exceeds the per-category cap"),
                "expected aggregate-cap message, got: " + ex.getMessage());
        verify(catRepo, never()).save(any());
        verify(seatRepo, never()).saveAll(anyList());
    }

    @Test
    void createCategory_acceptsSeatsExactlyAtAggregateCap() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);

        // 5 × 100,000 = 500,000 — exactly the cap, must succeed.
        List<SectionSeatConfigDTO> sections = List.of(
                section("A", 100_000), section("B", 100_000), section("C", 100_000),
                section("D", 100_000), section("E", 100_000));

        service.createCategory(request(UUID.randomUUID(), "MaxCap", sections));

        ArgumentCaptor<SeatCategory> savedCategory = ArgumentCaptor.forClass(SeatCategory.class);
        verify(catRepo).save(savedCategory.capture());
        assertEquals(500_000, savedCategory.getValue().getTotalSeats());
    }

    @Test
    void deleteCategory_softDeletes() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatCategoryService service = service(catRepo, mock(SeatRepository.class));

        UUID id = UUID.randomUUID();
        SeatCategory category = SeatCategory.builder().id(id).name("VIP").deleted(false).build();
        when(catRepo.findById(id)).thenReturn(Optional.of(category));

        service.deleteCategory(id);

        assertTrue(category.isDeleted());
        verify(catRepo).save(category);
    }

    @Test
    void getCategoriesByEvent_setsLiveAvailableSeats_fromBookingServiceCount() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID vip = UUID.randomUUID();
        UUID ga = UUID.randomUUID();

        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(
                category(vip, eventId, 50, 50), category(ga, eventId, 50, 50)));
        when(seatRepo.findByCategoryIdIn(anyList())).thenReturn(List.of());
        when(booking.fetchActiveCountsByCategories(any()))
                .thenReturn(Optional.of(Map.of(vip, 37L, ga, 12L)));

        Map<UUID, Integer> avail = availabilityByCategory(
                service(catRepo, seatRepo, booking).getCategoriesByEvent(eventId));

        assertEquals(13, avail.get(vip), "50 total − 37 active");
        assertEquals(38, avail.get(ga), "50 total − 12 active");
    }

    @Test
    void getCategoriesByEvent_categoryWithNoActiveBookings_readsFullCapacity() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID vip = UUID.randomUUID();
        UUID ga = UUID.randomUUID();

        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(
                category(vip, eventId, 50, 50), category(ga, eventId, 50, 50)));
        when(seatRepo.findByCategoryIdIn(anyList())).thenReturn(List.of());
        // Only VIP appears in the counts; GA is absent → no active bookings → full.
        when(booking.fetchActiveCountsByCategories(any()))
                .thenReturn(Optional.of(Map.of(vip, 50L)));

        Map<UUID, Integer> avail = availabilityByCategory(
                service(catRepo, seatRepo, booking).getCategoriesByEvent(eventId));

        assertEquals(0, avail.get(vip), "sold out");
        assertEquals(50, avail.get(ga), "absent from counts → full capacity");
    }

    @Test
    void getCategoriesByEvent_clampsNegativeToZero_whenCountExceedsCapacity() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID vip = UUID.randomUUID();

        when(catRepo.findByEventIdAndDeletedFalse(eventId))
                .thenReturn(List.of(category(vip, eventId, 50, 50)));
        when(seatRepo.findByCategoryIdIn(anyList())).thenReturn(List.of());
        when(booking.fetchActiveCountsByCategories(any()))
                .thenReturn(Optional.of(Map.of(vip, 60L)));

        Map<UUID, Integer> avail = availabilityByCategory(
                service(catRepo, seatRepo, booking).getCategoriesByEvent(eventId));

        assertEquals(0, avail.get(vip), "never negative");
    }

    @Test
    void getCategoriesByEvent_bookingServiceDown_fallsBackToStoredMirror() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID vip = UUID.randomUUID();

        // Stored mirror says 20 available; booking-service is unreachable.
        when(catRepo.findByEventIdAndDeletedFalse(eventId))
                .thenReturn(List.of(category(vip, eventId, 50, 20)));
        when(seatRepo.findByCategoryIdIn(anyList())).thenReturn(List.of());
        when(booking.fetchActiveCountsByCategories(any())).thenReturn(Optional.empty());

        Map<UUID, Integer> avail = availabilityByCategory(
                service(catRepo, seatRepo, booking).getCategoriesByEvent(eventId));

        assertEquals(20, avail.get(vip), "falls back to stored availableSeats");
    }

    private Map<UUID, Integer> availabilityByCategory(List<CreateCategoryResponseDTO> result) {
        return result.stream().collect(Collectors.toMap(
                CreateCategoryResponseDTO::getSeatCategoryId,
                CreateCategoryResponseDTO::getAvailableSeats));
    }
}
