package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.dto.UpdateCategoryRequestDTO;
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

    private SectionSeatConfigDTO section(String label, int count, String imageUrl) {
        SectionSeatConfigDTO s = section(label, count);
        s.setImageUrl(imageUrl);
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
    void createCategory_stampsSectionImageOnEverySeat_andEchoesInResponse() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);
        UUID eventId = UUID.randomUUID();
        String imgA = "https://cdn.innbucks.co.zw/sections/vip-a.png";

        CreateCategoryResponseDTO resp = service.createCategory(request(eventId, "VIP",
                List.of(section("A", 2, imgA), section("B", 1, null))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepo).saveAll(seatsCaptor.capture());
        List<Seat> saved = seatsCaptor.getValue();
        // Every seat in section A carries the image; section B's seats carry none.
        assertTrue(saved.stream().filter(s -> s.getSectionLabel().equals("A"))
                .allMatch(s -> imgA.equals(s.getSectionImageUrl())));
        assertTrue(saved.stream().filter(s -> s.getSectionLabel().equals("B"))
                .allMatch(s -> s.getSectionImageUrl() == null));

        // Response echoes the per-section image from the request.
        Map<String, String> imgBySection = resp.getSections().stream()
                .collect(java.util.HashMap::new,
                        (m, s) -> m.put(s.getSection(), s.getImageUrl()), java.util.HashMap::putAll);
        assertEquals(imgA, imgBySection.get("A"));
        assertNull(imgBySection.get("B"));
    }

    @Test
    void createCategory_blankImageUrl_storedAsNull() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = service(catRepo, seatRepo);

        service.createCategory(request(UUID.randomUUID(), "VIP", List.of(section("A", 2, "   "))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepo).saveAll(seatsCaptor.capture());
        assertTrue(seatsCaptor.getValue().stream().allMatch(s -> s.getSectionImageUrl() == null),
                "blank image URL must normalize to null, not a blank string");
    }

    @Test
    void getCategoriesByEvent_recoversSectionImage_fromSeats() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        SeatCategory cat = category(catId, eventId, 3, 3);
        String imgA = "https://cdn.innbucks.co.zw/sections/a.png";

        when(catRepo.findByEventIdAndDeletedFalse(eventId)).thenReturn(List.of(cat));
        // Two A seats carry the image, one B seat carries none.
        when(seatRepo.findByCategoryIdIn(anyList())).thenReturn(List.of(
                Seat.builder().category(cat).sectionLabel("A").sectionImageUrl(imgA).build(),
                Seat.builder().category(cat).sectionLabel("A").sectionImageUrl(imgA).build(),
                Seat.builder().category(cat).sectionLabel("B").build()));
        when(booking.fetchActiveCountsByCategories(any())).thenReturn(Optional.empty());

        CreateCategoryResponseDTO dto = service(catRepo, seatRepo, booking)
                .getCategoriesByEvent(eventId).get(0);

        Map<String, String> imgBySection = dto.getSections().stream()
                .collect(java.util.HashMap::new,
                        (m, s) -> m.put(s.getSection(), s.getImageUrl()), java.util.HashMap::putAll);
        assertEquals(imgA, imgBySection.get("A"));
        assertNull(imgBySection.get("B"), "section with no image reads back null");
    }

    @Test
    void createCategory_rejectsZeroPrice() {
        // A $0 seat category isn't a domain we support — even a free event
        // still gets a positive price set; the organizer doesn't get to mint a
        // category whose tickets bypass payment. Bean validation catches this
        // at the controller (@DecimalMin inclusive=false), but the service
        // re-checks as defence-in-depth so S2S / direct callers can't bypass.
        SeatCategoryService service = service(
                mock(SeatCategoryRepository.class), mock(SeatRepository.class));
        CreateCategoryRequestDTO req = request(UUID.randomUUID(), "VIP",
                List.of(section("A", 5)));
        req.setPrice(BigDecimal.ZERO);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(req));
        assertTrue(ex.getMessage().contains("Price must be greater than 0"),
                "actual: " + ex.getMessage());
    }

    @Test
    void createCategory_rejectsNegativePrice() {
        SeatCategoryService service = service(
                mock(SeatCategoryRepository.class), mock(SeatRepository.class));
        CreateCategoryRequestDTO req = request(UUID.randomUUID(), "VIP",
                List.of(section("A", 5)));
        req.setPrice(new BigDecimal("-1.00"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(req));
        assertTrue(ex.getMessage().contains("Price must be greater than 0"));
    }

    @Test
    void createCategory_rejectsNullPrice() {
        // Belt-and-braces — bean validation's @NotNull catches this at the
        // controller, but a service-level call (or a future broken caller)
        // must not silently insert NULL into the price column.
        SeatCategoryService service = service(
                mock(SeatCategoryRepository.class), mock(SeatRepository.class));
        CreateCategoryRequestDTO req = request(UUID.randomUUID(), "VIP",
                List.of(section("A", 5)));
        req.setPrice(null);

        assertThrows(RuntimeException.class, () -> service.createCategory(req));
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

    private UpdateCategoryRequestDTO updateRequest(String name, String description, String price) {
        UpdateCategoryRequestDTO req = new UpdateCategoryRequestDTO();
        req.setName(name);
        req.setDescription(description);
        req.setPrice(new BigDecimal(price));
        return req;
    }

    @Test
    void updateCategory_appliesNameDescriptionPrice_andReturnsRebuiltSections() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        BookingServiceClient booking = mock(BookingServiceClient.class);
        UUID eventId = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        SeatCategory existing = category(id, eventId, 50, 50);
        existing.setName("VIP");
        when(catRepo.findById(id)).thenReturn(Optional.of(existing));
        when(catRepo.existsByEventIdAndNameAndDeletedFalseAndIdNot(eventId, "VVIP", id)).thenReturn(false);
        // Sections come back from the persisted seats, not the request.
        when(seatRepo.findByCategoryIdIn(List.of(id))).thenReturn(List.of(
                Seat.builder().sectionLabel("A").seatNumber(1).build(),
                Seat.builder().sectionLabel("A").seatNumber(2).build(),
                Seat.builder().sectionLabel("B").seatNumber(1).build()));
        when(booking.fetchActiveCountsByCategories(List.of(id)))
                .thenReturn(Optional.of(Map.of(id, 13L)));

        CreateCategoryResponseDTO resp = service(catRepo, seatRepo, booking)
                .updateCategory(id, updateRequest("VVIP", "Now with lounge", "120.00"));

        // Entity mutated + persisted.
        assertEquals("VVIP", existing.getName());
        assertEquals("Now with lounge", existing.getDescription());
        assertEquals(new BigDecimal("120.00"), existing.getPrice());
        verify(catRepo).save(existing);

        // Response carries the new metadata, sections rebuilt from seats (A:2, B:1),
        // and LIVE availability (50 total − 13 active = 37).
        assertEquals("VVIP", resp.getName());
        assertEquals(37, resp.getAvailableSeats());
        Map<String, Integer> sections = resp.getSections().stream()
                .collect(Collectors.toMap(SectionSeatConfigDTO::getSection, SectionSeatConfigDTO::getSeatCount));
        assertEquals(2, sections.get("A"));
        assertEquals(1, sections.get("B"));
    }

    @Test
    void updateCategory_renameToOwnCurrentName_isAllowed() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SeatCategory existing = category(id, eventId, 10, 10);
        existing.setName("VIP");
        when(catRepo.findById(id)).thenReturn(Optional.of(existing));
        // ...AndIdNot excludes self, so the same-name check returns false → no conflict.
        when(catRepo.existsByEventIdAndNameAndDeletedFalseAndIdNot(eventId, "VIP", id)).thenReturn(false);
        when(seatRepo.findByCategoryIdIn(List.of(id))).thenReturn(List.of());

        assertDoesNotThrow(() -> service(catRepo, seatRepo)
                .updateCategory(id, updateRequest("VIP", "tweaked copy", "75.00")));
        verify(catRepo).save(existing);
    }

    @Test
    void updateCategory_rejectsNameTakenByAnotherCategory() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SeatCategory existing = category(id, eventId, 10, 10);
        when(catRepo.findById(id)).thenReturn(Optional.of(existing));
        when(catRepo.existsByEventIdAndNameAndDeletedFalseAndIdNot(eventId, "GA", id)).thenReturn(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service(catRepo, seatRepo)
                .updateCategory(id, updateRequest("GA", null, "50.00")));
        assertTrue(ex.getMessage().contains("already exists"), "actual: " + ex.getMessage());
        verify(catRepo, never()).save(any());
    }

    @Test
    void updateCategory_throwsNotFound_whenMissingOrDeleted() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        UUID id = UUID.randomUUID();
        when(catRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service(catRepo, mock(SeatRepository.class))
                .updateCategory(id, updateRequest("VIP", null, "50.00")));

        // A soft-deleted category is treated as not-found too.
        SeatCategory deleted = category(id, UUID.randomUUID(), 10, 10);
        deleted.setDeleted(true);
        when(catRepo.findById(id)).thenReturn(Optional.of(deleted));
        assertThrows(RuntimeException.class, () -> service(catRepo, mock(SeatRepository.class))
                .updateCategory(id, updateRequest("VIP", null, "50.00")));
    }

    @Test
    void updateCategory_rejectsNonPositivePrice() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(catRepo.findById(id)).thenReturn(Optional.of(category(id, eventId, 10, 10)));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service(catRepo, seatRepo)
                .updateCategory(id, updateRequest("VIP", null, "0.00")));
        assertTrue(ex.getMessage().contains("Price must be greater than 0"), "actual: " + ex.getMessage());
        verify(catRepo, never()).save(any());
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
