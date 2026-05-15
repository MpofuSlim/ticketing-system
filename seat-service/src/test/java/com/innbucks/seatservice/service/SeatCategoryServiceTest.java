package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.CreateCategoryRequestDTO;
import com.innbucks.seatservice.dto.CreateCategoryResponseDTO;
import com.innbucks.seatservice.dto.SectionSeatConfigDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SeatCategoryServiceTest {

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

    @Test
    void createCategory_autoGeneratesSeatsPerSection_andInitializesAvailability() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = new SeatCategoryService(catRepo, seatRepo, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
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
    }

    @Test
    void createCategory_rejectsDuplicateCategoryNameForSameEvent() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = new SeatCategoryService(catRepo, seatRepo, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
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
        SeatCategoryService service = new SeatCategoryService(
                mock(SeatCategoryRepository.class), mock(SeatRepository.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createCategory(request(UUID.randomUUID(), "VIP",
                        List.of(section("A", 3), section(" a ", 2)))));
        assertTrue(ex.getMessage().contains("Duplicate section"));
    }

    @Test
    void createCategory_normalizesSectionLabelToUppercase() {
        SeatCategoryRepository catRepo = mock(SeatCategoryRepository.class);
        SeatRepository seatRepo = mock(SeatRepository.class);
        SeatCategoryService service = new SeatCategoryService(catRepo, seatRepo, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

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
        SeatCategoryService service = new SeatCategoryService(catRepo, seatRepo,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

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
        SeatCategoryService service = new SeatCategoryService(catRepo, seatRepo,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

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
        SeatCategoryService service = new SeatCategoryService(catRepo, mock(SeatRepository.class), org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

        UUID id = UUID.randomUUID();
        SeatCategory category = SeatCategory.builder().id(id).name("VIP").deleted(false).build();
        when(catRepo.findById(id)).thenReturn(java.util.Optional.of(category));

        service.deleteCategory(id);

        assertTrue(category.isDeleted());
        verify(catRepo).save(category);
    }
}
