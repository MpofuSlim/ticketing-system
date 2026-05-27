package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private static final long MAX_BANNER_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_BANNER_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final SeatCategoryGateway seatCategoryGateway;
    private final BookingGateway bookingGateway;

    public Page<EventResponseDTO> getAllActiveEvents(
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active events from={} to={} venue={} page={} size={} sortBy={}",
                from, to, venue, page, size, sortBy);
        return enrichWithAvailability(eventRepository.findAllActive(from, to, venue, pageable));
    }

    public Page<EventResponseDTO> getActiveOnlyEvents(
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active=true events from={} to={} venue={} page={} size={} sortBy={}",
                from, to, venue, page, size, sortBy);
        return enrichWithAvailability(eventRepository.findAllActiveOnly(from, to, venue, pageable));
    }

    /**
     * List the events owned by a single tenant (the authenticated organizer).
     * SUPER_ADMIN should call {@link #getAllActiveEvents} instead — they see
     * everything. This endpoint is the per-organizer scoped view.
     */
    public Page<EventResponseDTO> getMyEvents(
            String tenantId,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching events for tenantId={} from={} to={} venue={} page={} size={} sortBy={}",
                tenantId, from, to, venue, page, size, sortBy);
        return enrichWithAvailability(
                eventRepository.findByTenantId(tenantId, from, to, venue, pageable));
    }

    /**
     * Same as {@link #getMyEvents} but additionally filters {@code active=true}.
     * Used so an EVENT_ORGANIZER hitting the public {@code /events/active}
     * listing only sees their own bookable events.
     */
    public Page<EventResponseDTO> getMyActiveEvents(
            String tenantId,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active events for tenantId={} from={} to={} venue={} page={} size={} sortBy={}",
                tenantId, from, to, venue, page, size, sortBy);
        return enrichWithAvailability(
                eventRepository.findByTenantIdActiveOnly(tenantId, from, to, venue, pageable));
    }

    public EventResponseDTO getEventById(UUID eventId) {
        log.debug("Fetching event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Event not found eventId={}", eventId);
                    return new RuntimeException("Event not found");
                });
        EventResponseDTO response = toDtoWithAvailability(event, fetchActiveCounts(eventId));
        response.setSeatCategories(seatCategoryGateway.fetchForEvent(eventId));
        return response;
    }

    public Page<EventResponseDTO> searchEvents(
            String q,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Searching events q={} page={} size={} sortBy={}", q, page, size, sortBy);
        return enrichWithAvailability(
                eventRepository.searchByKeyword(q == null ? "" : q.trim(), pageable));
    }

    public Page<EventResponseDTO> getEventsByCountry(
            String country,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("startDateTime").ascending());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        log.debug("Fetching active upcoming events by country={} cutoff={} page={} size={}",
                country, now, page, size);

        Page<Event> entities = eventRepository
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndStartDateTimeGreaterThanEqual(
                        country, now, pageable);

        Map<UUID, Long> activeCounts = bookingGateway.activeCountsByEventIds(
                entities.getContent().stream().map(Event::getEventId).toList());

        List<EventResponseDTO> dtos = new ArrayList<>(entities.getNumberOfElements());
        int n = 1;
        for (Event event : entities.getContent()) {
            EventResponseDTO dto = toDtoWithAvailability(event, activeCounts);
            dto.setEventNo(n++);
            dtos.add(dto);
        }
        return new PageImpl<>(dtos, pageable, entities.getTotalElements());
    }

    // Returns the event mapped to its DTO with availableTickets recomputed as
    // (totalCapacity − active booking items). Falls back to the stored value
    // when booking-service is unreachable (the gateway returns an empty map).
    private EventResponseDTO toDtoWithAvailability(Event event, Map<UUID, Long> activeCounts) {
        EventResponseDTO dto = eventMapper.toDTO(event);
        Long active = activeCounts == null ? null : activeCounts.get(event.getEventId());
        if (active != null && event.getTotalCapacity() != null) {
            int remaining = Math.max(0, event.getTotalCapacity() - active.intValue());
            dto.setAvailableTickets(remaining);
        }
        return dto;
    }

    private Page<EventResponseDTO> enrichWithAvailability(Page<Event> page) {
        List<UUID> ids = page.getContent().stream()
                .map(Event::getEventId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, Long> activeCounts = ids.isEmpty()
                ? java.util.Collections.emptyMap()
                : bookingGateway.activeCountsByEventIds(ids);
        if (activeCounts == null) activeCounts = java.util.Collections.emptyMap();
        Map<UUID, Long> finalCounts = activeCounts;
        return page.map(e -> toDtoWithAvailability(e, finalCounts));
    }

    private Map<UUID, Long> fetchActiveCounts(UUID eventId) {
        if (eventId == null) {
            return java.util.Collections.emptyMap();
        }
        Map<UUID, Long> counts = bookingGateway.activeCountsByEventIds(List.of(eventId));
        return counts == null ? java.util.Collections.emptyMap() : counts;
    }

    public EventResponseDTO createEvent(String tenantId, String country, CreateEventRequestDTO request) {
        return createEvent(tenantId, country, request, null);
    }

    @Transactional
    public EventResponseDTO createEvent(String tenantId, String country, CreateEventRequestDTO request, MultipartFile eventBanner) {
        log.info("Creating event tenantId={} country={} title={} venue={} startDateTime={} capacity={} hasBanner={}",
                tenantId, country, request.getTitle(), request.getVenue(), request.getStartDateTime(), request.getTotalCapacity(),
                eventBanner != null && !eventBanner.isEmpty());

        // Country is stamped from the organizer's JWT, never the request body.
        // A token minted before country was captured won't carry the claim;
        // reject rather than persist an event with no country.
        if (country == null || country.isBlank()) {
            log.warn("Event create rejected, missing country claim on token tenantId={}", tenantId);
            throw new RuntimeException("Country is required but was not present in your token");
        }

        if (eventRepository.existsByTenantIdAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                tenantId, request.getTitle(), request.getVenue(), request.getStartDateTime())) {
            log.warn("Event create rejected, duplicate tenantId={} title={} venue={} startDateTime={}",
                    tenantId, request.getTitle(), request.getVenue(), request.getStartDateTime());
            throw new RuntimeException("An event with the same title, venue and date already exists");
        }

        Event event = Event.builder()
                .tenantId(tenantId)
                .title(request.getTitle())
                .description(request.getDescription())
                .venue(request.getVenue())
                .country(country)
                .category(request.getCategory())
                .location(toLocation(request.getLocation()))
                .startDateTime(request.getStartDateTime())
                .endDateTime(request.getEndDateTime())
                .totalCapacity(request.getTotalCapacity())
                .availableTickets(request.getTotalCapacity())
                .deleted(false)
                // Newly created events start inactive; the tenant flips active=true
                // via PUT /events/{id}/activate once they're ready to publish.
                .active(false)
                .build();

        applyBanner(event, eventBanner);

        Event saved = eventRepository.save(event);
        log.info("Event created eventId={} tenantId={}", saved.getEventId(), tenantId);
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional(readOnly = true)
    public BannerImage getEventBanner(UUID eventId) {
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));
        if (event.getBannerImage() == null || event.getBannerImage().length == 0) {
            throw new RuntimeException("Banner not found");
        }
        return new BannerImage(event.getBannerImage(), event.getBannerContentType());
    }

    public record BannerImage(byte[] bytes, String contentType) {}

    private static Location toLocation(LocationDTO dto) {
        if (dto == null) return null;
        return Location.builder()
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .build();
    }

    private static void applyBanner(Event event, MultipartFile file) {
        if (file == null || file.isEmpty()) return;
        if (file.getSize() > MAX_BANNER_BYTES) {
            throw new RuntimeException("Banner image is too large (max 5MB)");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_BANNER_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new RuntimeException("Banner image must be JPEG, PNG, GIF or WEBP");
        }
        try {
            event.setBannerImage(file.getBytes());
            event.setBannerContentType(contentType.toLowerCase());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read banner image", e);
        }
    }

    @Transactional
    public EventResponseDTO updateEvent(String tenantId, String role, UUID eventId, UpdateEventRequestDTO request) {
        log.info("Updating event eventId={} tenantId={} role={} req.title={} req.venue={} req.startDateTime={} req.endDateTime={} req.totalCapacity={}",
                eventId, tenantId, role,
                request.getTitle(), request.getVenue(), request.getStartDateTime(), request.getEndDateTime(), request.getTotalCapacity());
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Update failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can update only own event; 
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized update attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new RuntimeException("You are not authorized to update this event");
        }

        if (request.getTitle() != null)        event.setTitle(request.getTitle());
        if (request.getDescription() != null)  event.setDescription(request.getDescription());
        if (request.getVenue() != null)        event.setVenue(request.getVenue());
        if (request.getCategory() != null)     event.setCategory(request.getCategory());
        if (request.getLocation() != null)     event.setLocation(toLocation(request.getLocation()));
        if (request.getStartDateTime() != null) event.setStartDateTime(request.getStartDateTime());
        if (request.getEndDateTime() != null)   event.setEndDateTime(request.getEndDateTime());
        // Guard the merged result: a single-sided update (only start or only
        // end) could still invert the order against the stored value, which
        // the request-level @AssertTrue can't catch.
        if (event.getStartDateTime() != null && event.getEndDateTime() != null
                && !event.getEndDateTime().isAfter(event.getStartDateTime())) {
            throw new RuntimeException("endDateTime must be after startDateTime");
        }
        if (request.getTotalCapacity() != null) {
            int diff = request.getTotalCapacity() - event.getTotalCapacity();
            event.setTotalCapacity(request.getTotalCapacity());
            event.setAvailableTickets(event.getAvailableTickets() + diff);
        }

        Event saved = eventRepository.save(event);
        log.info("Event updated eventId={} tenantId={} title={} venue={} startDateTime={} endDateTime={}",
                eventId, tenantId, saved.getTitle(), saved.getVenue(), saved.getStartDateTime(), saved.getEndDateTime());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    public EventResponseDTO updateEvent(String tenantId, UUID eventId, UpdateEventRequestDTO request) {
        return updateEvent(tenantId, "ROLE_EVENT_ORGANIZER", eventId, request);
    }

    // Internal: called by booking-service when a booking transitions to
    // CONFIRMED so the event's stored availableTickets matches reality.
    // Returns the new availableTickets value. Throws when the event is
    // missing or capacity would underflow.
    @Transactional
    public int consumeAvailability(UUID eventId, int count) {
        if (count <= 0) {
            throw new RuntimeException("count must be positive");
        }
        log.info("Consuming availability eventId={} count={}", eventId, count);
        int updated = eventRepository.decrementAvailableTickets(eventId, count);
        if (updated == 0) {
            // Either the event doesn't exist or available < count.
            Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                    .orElseThrow(() -> new RuntimeException("Event not found"));
            throw new RuntimeException("Insufficient availability: requested=" + count
                    + " available=" + event.getAvailableTickets());
        }
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));
        log.info("Availability consumed eventId={} count={} remaining={}",
                eventId, count, event.getAvailableTickets());
        return event.getAvailableTickets();
    }

    @Transactional
    public EventResponseDTO activateEvent(String tenantId, String role, UUID eventId) {
        log.info("Activating event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Activate failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized activate attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new RuntimeException("You are not authorized to activate this event");
        }

        event.setActive(true);
        Event saved = eventRepository.save(event);
        log.info("Event activated eventId={} tenantId={}", eventId, tenantId);
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional
    public void deleteEvent(String tenantId, String role, UUID eventId) {
        log.info("Deleting event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Delete failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new RuntimeException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can delete only own event; 
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized delete attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new RuntimeException("You are not authorized to delete this event");
        }

        event.setDeleted(true);
        eventRepository.save(event);
        log.info("Event deleted (soft) eventId={} tenantId={}", eventId, tenantId);
    }

    public void deleteEvent(String tenantId, UUID eventId) {
        deleteEvent(tenantId, "ROLE_EVENT_ORGANIZER", eventId);
    }

}
