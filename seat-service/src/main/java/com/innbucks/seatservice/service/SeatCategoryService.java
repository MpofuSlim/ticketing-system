package com.innbucks.seatservice.service;

import com.innbucks.seatservice.dto.*;
import com.innbucks.seatservice.entity.*;
import com.innbucks.seatservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatCategoryService {

    private final SeatCategoryRepository categoryRepository;
    private final SeatRepository seatRepository;

    // Agent creates a category for an event and auto-generates all seats
    @Transactional
    public CreateCategoryResponseDTO createCategory(CreateCategoryRequestDTO request) {
        log.info("Creating seat category eventId={} name={} sections={}",
                request.getEventId(), request.getName(), request.getSections().size());

        if (categoryRepository.existsByEventIdAndNameAndDeletedFalse(
                request.getEventId(), request.getName())) {
            log.warn("Category creation rejected, duplicate name eventId={} name={}",
                    request.getEventId(), request.getName());
            throw new RuntimeException("Category '" + request.getName()
                    + "' already exists for this event");
        }

        int totalSeats = request.getSections().stream()
                .mapToInt(SectionSeatConfigDTO::getSeatCount)
                .sum();

        // Prevent duplicated sections in one request, e.g. "A" and "a"
        Set<String> seenSections = new HashSet<>();
        for (SectionSeatConfigDTO sectionConfig : request.getSections()) {
            String normalized = sectionConfig.getSection().trim().toUpperCase(Locale.ROOT);
            if (!seenSections.add(normalized)) {
                log.warn("Category creation rejected, duplicate section eventId={} name={} section={}",
                        request.getEventId(), request.getName(), normalized);
                throw new RuntimeException("Duplicate section '" + normalized + "' in request");
            }
        }

        SeatCategory category = SeatCategory.builder()
                .eventId(request.getEventId())
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .totalSeats(totalSeats)
                .availableSeats(totalSeats)
                .deleted(false)
                .build();

        categoryRepository.save(category);

        // Auto-generate seats with per-section capacity, e.g. A1-A5, B1-B7, C1-C8
        List<Seat> seats = new ArrayList<>();
        for (SectionSeatConfigDTO sectionConfig : request.getSections()) {
            String sectionLabel = sectionConfig.getSection().trim().toUpperCase(Locale.ROOT);
            for (int num = 1; num <= sectionConfig.getSeatCount(); num++) {
                seats.add(Seat.builder()
                        .category(category)
                        .sectionLabel(sectionLabel)
                        .seatNumber(num)
                        .status(Seat.SeatStatus.AVAILABLE)
                        .build());
            }
        }
        seatRepository.saveAll(seats);

        log.info("Seat category created categoryId={} eventId={} name={} totalSeats={}",
                category.getId(), request.getEventId(), request.getName(), totalSeats);
        return toCreateResponseDTO(category, request.getSections());
    }

    // Get all categories for an event
    public List<CreateCategoryResponseDTO> getCategoriesByEvent(UUID eventId) {
        log.debug("Fetching seat categories eventId={}", eventId);
        List<SeatCategory> categories = categoryRepository.findByEventIdAndDeletedFalse(eventId);
        List<UUID> categoryIds = categories.stream()
                .map(SeatCategory::getId)
                .collect(Collectors.toList());

        Map<UUID, List<SectionSeatConfigDTO>> sectionsByCategory = new LinkedHashMap<>();
        if (!categoryIds.isEmpty()) {
            Map<UUID, Map<String, Integer>> grouped = new LinkedHashMap<>();
            for (Seat seat : seatRepository.findByCategoryIdIn(categoryIds)) {
                UUID categoryId = seat.getCategory().getId();
                grouped.computeIfAbsent(categoryId, ignored -> new LinkedHashMap<>());
                Map<String, Integer> sectionCounts = grouped.get(categoryId);
                sectionCounts.put(seat.getSectionLabel(), sectionCounts.getOrDefault(seat.getSectionLabel(), 0) + 1);
            }

            for (Map.Entry<UUID, Map<String, Integer>> entry : grouped.entrySet()) {
                List<SectionSeatConfigDTO> sections = entry.getValue().entrySet().stream()
                        .map(sectionEntry -> {
                            SectionSeatConfigDTO dto = new SectionSeatConfigDTO();
                            dto.setSection(sectionEntry.getKey());
                            dto.setSeatCount(sectionEntry.getValue());
                            return dto;
                        })
                        .collect(Collectors.toList());
                sectionsByCategory.put(entry.getKey(), sections);
            }
        }

        return categories.stream()
                .map(category -> toCreateResponseDTO(
                        category,
                        sectionsByCategory.getOrDefault(category.getId(), List.of())
                ))
                .collect(Collectors.toList());
    }

    // Soft delete a category
    @Transactional
    public void deleteCategory(UUID categoryId) {
        log.info("Soft-deleting seat category categoryId={}", categoryId);
        SeatCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> {
                    log.warn("Category delete failed, not found categoryId={}", categoryId);
                    return new RuntimeException("Category not found");
                });
        category.setDeleted(true);
        categoryRepository.save(category);
        log.info("Seat category soft-deleted categoryId={} eventId={}", categoryId, category.getEventId());
    }

    private CategoryResponseDTO toDTO(SeatCategory c) {
        return CategoryResponseDTO.builder()
                .id(c.getId())
                .eventId(c.getEventId())
                .name(c.getName())
                .description(c.getDescription())
                .price(c.getPrice())
                .totalSeats(c.getTotalSeats())
                .availableSeats(c.getAvailableSeats())
                .createdAt(c.getCreatedAt())
                .build();
    }

    private CreateCategoryResponseDTO toCreateResponseDTO(
            SeatCategory category,
            List<SectionSeatConfigDTO> sections
    ) {
        List<SectionSeatConfigDTO> sectionCopies = sections.stream()
                .map(section -> {
                    SectionSeatConfigDTO dto = new SectionSeatConfigDTO();
                    dto.setSection(section.getSection());
                    dto.setSeatCount(section.getSeatCount());
                    return dto;
                })
                .collect(Collectors.toList());

        return CreateCategoryResponseDTO.builder()
                .eventId(category.getEventId())
                .name(category.getName())
                .description(category.getDescription())
                .price(category.getPrice())
                .sections(sectionCopies)
                .build();
    }
}
