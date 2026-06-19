package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.*;
import com.innbucks.seatservice.entity.*;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.exception.NotFoundException;
import com.innbucks.seatservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
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

    // Aggregate cap on top of the DTO's per-section @Max + sections @Size.
    // A request that passes bean validation can still ask for 100 sections ×
    // 100,000 seats = 10 million. This cap rejects that before we materialise
    // the seat rows. Half a million is more than any real venue's capacity.
    public static final long MAX_TOTAL_SEATS_PER_CATEGORY = 500_000L;

    private final SeatCategoryRepository categoryRepository;
    private final SeatRepository seatRepository;
    private final BookingServiceClient bookingServiceClient;
    private final ObjectProvider<EventServiceClient> eventClientProvider;

    @Transactional
    public CreateCategoryResponseDTO createCategory(CreateCategoryRequestDTO request) {
        return createCategory(request, null, null, true, null);
    }

    @Transactional
    public CreateCategoryResponseDTO createCategory(CreateCategoryRequestDTO request,
                                                    UUID callerOrganizerUuid,
                                                    String requesterEmail,
                                                    boolean isAdmin,
                                                    String authHeader) {
        if (!isAdmin) {
            requireEventOwnership(request.getEventId(), callerOrganizerUuid, requesterEmail, authHeader);
        }
        log.info("Creating seat category eventId={} name={} sections={} requesterEmail={} isAdmin={}",
                request.getEventId(), request.getName(), request.getSections().size(),
                requesterEmail, isAdmin);

        if (categoryRepository.existsByEventIdAndNameAndDeletedFalse(
                request.getEventId(), request.getName())) {
            log.warn("Category creation rejected, duplicate name eventId={} name={}",
                    request.getEventId(), request.getName());
            throw new ConflictException("Category '" + request.getName()
                    + "' already exists for this event");
        }

        // Sum in long to dodge int overflow when validation has already
        // passed but section counts approach Integer.MAX_VALUE collectively.
        long totalSeatsLong = request.getSections().stream()
                .mapToLong(SectionSeatConfigDTO::getSeatCount)
                .sum();
        if (totalSeatsLong > MAX_TOTAL_SEATS_PER_CATEGORY) {
            log.warn("Category creation rejected, total seats exceeds cap eventId={} requested={} cap={}",
                    request.getEventId(), totalSeatsLong, MAX_TOTAL_SEATS_PER_CATEGORY);
            throw new BadRequestException("This category has too many seats (" + totalSeatsLong
                    + ") — it exceeds the per-category cap of " + MAX_TOTAL_SEATS_PER_CATEGORY + ".");
        }
        int totalSeats = (int) totalSeatsLong;

        // Prevent duplicated sections in one request, e.g. "A" and "a"
        Set<String> seenSections = new HashSet<>();
        for (SectionSeatConfigDTO sectionConfig : request.getSections()) {
            String normalized = sectionConfig.getSection().trim().toUpperCase(Locale.ROOT);
            if (!seenSections.add(normalized)) {
                log.warn("Category creation rejected, duplicate section eventId={} name={} section={}",
                        request.getEventId(), request.getName(), normalized);
                throw new BadRequestException("Duplicate section '" + normalized + "' — you've listed it more than once. Please combine the entries.");
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
        // Just created — no bookings yet, so the whole category is available.
        // Skip the booking-service round trip on the create path.
        return toCreateResponseDTO(category, request.getSections(), category.getAvailableSeats());
    }

    /**
     * Capacity + pricing of a single category, for booking-service's GA
     * inventory model. booking-service seeds its own per-category counter from
     * {@code totalSeats}, prices the booking from {@code price}, and validates
     * the category belongs to the event from {@code eventId} — all without
     * picking an individual seat. Read-only S2S call (the {@code /seat-categories/**}
     * GETs are permitAll in SecurityConfig).
     */
    @Transactional(readOnly = true)
    public CategoryCapacityDTO getCategoryCapacity(UUID categoryId) {
        log.debug("Fetching category capacity categoryId={}", categoryId);
        SeatCategory category = categoryRepository.findById(categoryId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> {
                    log.warn("Category capacity lookup failed, not found categoryId={}", categoryId);
                    return new NotFoundException("Seat category not found");
                });
        // Deliberately the stored mirror, NOT a live booking-service count.
        // This is the S2S endpoint booking-service hits on the booking hot path
        // to seed its own inventory counter — it reads totalSeats/price/eventId
        // and ignores availableSeats (it derives its own active count locally).
        // Calling booking-service back from here would put a circular round trip
        // on every booking for a value the caller discards. The live per-category
        // number is surfaced on the FE-facing list endpoint (getCategoriesByEvent)
        // instead.
        return CategoryCapacityDTO.builder()
                .seatCategoryId(category.getId())
                .eventId(category.getEventId())
                .name(category.getName())
                .price(category.getPrice())
                .totalSeats(category.getTotalSeats())
                .availableSeats(category.getAvailableSeats())
                .build();
    }

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

        // One booking-service round trip covers every category in the event;
        // null counts (booking-service down) → each category falls back to
        // its stored mirror inside liveAvailableSeats.
        Map<UUID, Long> counts = bookingServiceClient
                .fetchActiveCountsByCategories(categoryIds)
                .orElse(null);

        return categories.stream()
                .map(category -> toCreateResponseDTO(
                        category,
                        sectionsByCategory.getOrDefault(category.getId(), List.of()),
                        liveAvailableSeats(category, counts)
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteCategory(UUID categoryId) {
        deleteCategory(categoryId, null, null, true, null);
    }

    @Transactional
    public void deleteCategory(UUID categoryId,
                               UUID callerOrganizerUuid,
                               String requesterEmail,
                               boolean isAdmin,
                               String authHeader) {
        log.info("Soft-deleting seat category categoryId={} requesterEmail={} isAdmin={}",
                categoryId, requesterEmail, isAdmin);
        SeatCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> {
                    log.warn("Category delete failed, not found categoryId={}", categoryId);
                    return new NotFoundException("Category not found");
                });
        if (!isAdmin) {
            requireEventOwnership(category.getEventId(), callerOrganizerUuid, requesterEmail, authHeader);
        }
        category.setDeleted(true);
        categoryRepository.save(category);
        log.info("Seat category soft-deleted categoryId={} eventId={}", categoryId, category.getEventId());
    }

    /**
     * Looks up the event in event-service and throws AccessDeniedException if
     * the caller is not its owning organizer. SUPER_ADMIN callers are checked
     * at the controller layer and never reach here. Matches the caller's
     * {@code organizerUuid} JWT claim against the event's
     * {@code tenantUserUuid} (event-service V7 / PR #259 dropped the legacy
     * email-as-tenantId column the prior check compared against).
     */
    private void requireEventOwnership(UUID eventId, UUID callerOrganizerUuid,
                                       String requesterEmail, String authHeader) {
        if (callerOrganizerUuid == null) {
            log.warn("Event ownership rejected — caller has no organizerUuid claim eventId={} requesterEmail={}",
                    eventId, requesterEmail);
            throw new AccessDeniedException("You do not own this event");
        }
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing mutation for eventId={} to non-admin",
                    eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        var lookup = client.fetchEvent(eventId, authHeader);
        if (lookup.isEmpty()) {
            log.warn("Event ownership lookup empty eventId={}", eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        UUID ownerUuid = lookup.get().getTenantUserUuid();
        if (ownerUuid == null || !ownerUuid.equals(callerOrganizerUuid)) {
            log.warn("Event ownership check failed eventId={} requesterEmail={} callerOrganizerUuid={} ownerUuid={}",
                    eventId, requesterEmail, callerOrganizerUuid, ownerUuid);
            throw new AccessDeniedException("You do not own this event");
        }
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
            List<SectionSeatConfigDTO> sections,
            Integer availableSeats
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
                .seatCategoryId(category.getId())
                .eventId(category.getEventId())
                .name(category.getName())
                .description(category.getDescription())
                .price(category.getPrice())
                .availableSeats(availableSeats)
                .sections(sectionCopies)
                .build();
    }

    /**
     * Live tickets still sellable in a category: {@code totalSeats} minus the
     * count of active (PENDING + CONFIRMED) bookings booking-service reports.
     * The same {@code totalCapacity − activeCount} formula event-service uses
     * for an event's availableTickets, so the per-category numbers and the
     * event card always tally.
     *
     * <p>When {@code counts} is {@code null} (booking-service unreachable) we
     * degrade to the category's stored {@code availableSeats} mirror rather
     * than fail the read — a public category listing must not 500 because
     * booking-service is down. A category absent from a non-null {@code counts}
     * map has zero active bookings, so it reads as full capacity.
     */
    private int liveAvailableSeats(SeatCategory category, Map<UUID, Long> counts) {
        int total = category.getTotalSeats() == null ? 0 : category.getTotalSeats();
        if (counts == null) {
            return category.getAvailableSeats() == null ? total : category.getAvailableSeats();
        }
        long active = counts.getOrDefault(category.getId(), 0L);
        return (int) Math.max(0L, total - active);
    }
}
