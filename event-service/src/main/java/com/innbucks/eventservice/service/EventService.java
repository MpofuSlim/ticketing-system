package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.dto.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.exception.BadRequestException;
import com.innbucks.eventservice.exception.ConflictException;
import com.innbucks.eventservice.exception.ForbiddenException;
import com.innbucks.eventservice.exception.NotFoundException;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final OrganizerGateway organizerGateway;
    private final BookingNotificationGateway bookingNotificationGateway;

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
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active=true events from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                from, to, venue, country, category, page, size, sortBy);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<Event> events = category == null
                ? eventRepository.findAllActiveOnly(from, to, venue, country, now, pageable)
                : eventRepository.findAllActiveOnlyByCategory(from, to, venue, country, category, now, pageable);
        return enrichWithAvailability(events);
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
     * UUID-keyed counterpart of {@link #getMyEvents(String, LocalDateTime,
     * LocalDateTime, String, int, int, String)}. Used by booking-service's
     * scanner flows ({@code /me/events}) where the caller (an EVENT_ORGANIZER
     * or one of their TEAM_MEMBERs) is identified by their organizerUuid
     * JWT claim rather than the legacy email pointer. Only returns events
     * whose {@code tenant_user_uuid} has been backfilled — pre-V6 rows that
     * haven't been migrated yet show up only via the email-keyed query
     * above. Once the backfill runner completes the two return the same set.
     */
    public Page<EventResponseDTO> getMyEventsByOrganizerUuid(
            UUID organizerUuid,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching events for organizerUuid={} from={} to={} venue={} page={} size={} sortBy={}",
                organizerUuid, from, to, venue, page, size, sortBy);
        return enrichWithAvailability(
                eventRepository.findByTenantUserUuid(organizerUuid, from, to, venue, pageable));
    }

    /**
     * Team-member view of {@code /events/my}: returns only the events the
     * member is explicitly assigned to (deny-by-default). The
     * {@code organizerUuid} comes from the JWT's {@code organizerUuid} claim
     * (the parent organizer's uuid for a TEAM_MEMBER) and is enforced as a
     * defence-in-depth ownership filter so a stale assignment row pointing
     * at a foreign organizer's event can never leak through.
     *
     * <p>An empty {@code assignedEventIds} short-circuits to an empty page —
     * Spring JPA forbids an empty {@code IN (...)} list in some dialects, and
     * a "no events" answer is the correct semantic in deny-by-default mode.
     */
    public Page<EventResponseDTO> getMyAssignedEvents(
            UUID organizerUuid,
            java.util.Collection<UUID> assignedEventIds,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        if (assignedEventIds == null || assignedEventIds.isEmpty()) {
            log.debug("Team-member has no event assignments organizerUuid={} -> empty page", organizerUuid);
            return Page.empty(pageable);
        }
        log.debug("Fetching assigned events for team-member organizerUuid={} assignedCount={} page={} size={}",
                organizerUuid, assignedEventIds.size(), page, size);
        return enrichWithAvailability(
                eventRepository.findByTenantUserUuidAndEventIdIn(
                        organizerUuid, assignedEventIds, from, to, venue, pageable));
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
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active events for tenantId={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                tenantId, from, to, venue, country, category, page, size, sortBy);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<Event> events = category == null
                ? eventRepository.findByTenantIdActiveOnly(tenantId, from, to, venue, country, now, pageable)
                : eventRepository.findByTenantIdActiveOnlyByCategory(tenantId, from, to, venue, country, category, now, pageable);
        return enrichWithAvailability(events);
    }

    /**
     * Public/admin-scoped listing of events flagged {@code active=false} — the
     * inactive listing behind {@code GET /events/inactive}. Mirrors
     * {@link #getActiveOnlyEvents} but intentionally includes events that have
     * already ended (no {@code endDateTime > now} cutoff) and admin-rejected
     * events (which are always {@code active=false}), so an admin can find a
     * rejected event here to approve it.
     */
    public Page<EventResponseDTO> getInactiveOnlyEvents(
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching active=false events from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                from, to, venue, country, category, page, size, sortBy);
        Page<Event> events = category == null
                ? eventRepository.findAllInactiveOnly(from, to, venue, country, pageable)
                : eventRepository.findAllInactiveOnlyByCategory(from, to, venue, country, category, pageable);
        return enrichWithAvailability(events);
    }

    /**
     * Same as {@link #getInactiveOnlyEvents} but scoped to a single tenant, so
     * an EVENT_ORGANIZER hitting {@code /events/inactive} only sees their own
     * inactive events.
     */
    public Page<EventResponseDTO> getMyInactiveEvents(
            String tenantId,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        log.debug("Fetching inactive events for tenantId={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                tenantId, from, to, venue, country, category, page, size, sortBy);
        Page<Event> events = category == null
                ? eventRepository.findByTenantIdInactiveOnly(tenantId, from, to, venue, country, pageable)
                : eventRepository.findByTenantIdInactiveOnlyByCategory(tenantId, from, to, venue, country, category, pageable);
        return enrichWithAvailability(events);
    }

    public EventResponseDTO getEventById(UUID eventId) {
        log.debug("Fetching event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Event not found eventId={}", eventId);
                    return new NotFoundException("Event not found");
                });
        EventResponseDTO response = toDtoWithAvailability(event, fetchActiveCounts(eventId));
        attachOrganizer(response, event, resolveOrganizers(List.of(event)));
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return enrichWithAvailability(
                eventRepository.searchByKeyword(q == null ? "" : q.trim(), now, pageable));
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
                .findByCountryIgnoreCaseAndDeletedFalseAndActiveTrueAndRejectedFalseAndStartDateTimeGreaterThanEqual(
                        country, now, pageable);

        Map<UUID, Long> activeCounts = bookingGateway.activeCountsByEventIds(
                entities.getContent().stream().map(Event::getEventId).toList());
        Map<String, OrganizerDTO> organizers = resolveOrganizers(entities.getContent());

        List<EventResponseDTO> dtos = new ArrayList<>(entities.getNumberOfElements());
        int n = 1;
        for (Event event : entities.getContent()) {
            EventResponseDTO dto = toDtoWithAvailability(event, activeCounts);
            attachOrganizer(dto, event, organizers);
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
        List<Event> content = page.getContent();
        List<UUID> ids = content.stream()
                .map(Event::getEventId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, Long> activeCounts = ids.isEmpty()
                ? Collections.emptyMap()
                : bookingGateway.activeCountsByEventIds(ids);
        if (activeCounts == null) activeCounts = Collections.emptyMap();
        Map<UUID, Long> finalCounts = activeCounts;
        Map<String, OrganizerDTO> organizers = resolveOrganizers(content);
        return page.map(e -> {
            EventResponseDTO dto = toDtoWithAvailability(e, finalCounts);
            attachOrganizer(dto, e, organizers);
            return dto;
        });
    }

    // Batch-resolve organizer (tenant) business details from user-service for
    // the events on this page. Returns an empty map if user-service is
    // unreachable (the gateway's circuit breaker falls back) so listings still
    // serve — just without organizer details.
    private Map<String, OrganizerDTO> resolveOrganizers(Collection<Event> events) {
        List<String> tenantIds = events.stream()
                .map(Event::getTenantId)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        Map<String, OrganizerDTO> organizers = organizerGateway.organizersByTenantIds(tenantIds);
        return organizers == null ? Collections.emptyMap() : organizers;
    }

    private void attachOrganizer(EventResponseDTO dto, Event event, Map<String, OrganizerDTO> organizers) {
        if (dto != null && organizers != null && event.getTenantId() != null) {
            dto.setOrganizer(organizers.get(event.getTenantId()));
        }
    }

    private Map<UUID, Long> fetchActiveCounts(UUID eventId) {
        if (eventId == null) {
            return java.util.Collections.emptyMap();
        }
        Map<UUID, Long> counts = bookingGateway.activeCountsByEventIds(List.of(eventId));
        return counts == null ? java.util.Collections.emptyMap() : counts;
    }

    public EventResponseDTO createEvent(String tenantId, String country, CreateEventRequestDTO request) {
        return createEvent(tenantId, null, country, request, null);
    }

    public EventResponseDTO createEvent(String tenantId, UUID tenantUserUuid, String country,
                                        CreateEventRequestDTO request) {
        return createEvent(tenantId, tenantUserUuid, country, request, null);
    }

    public EventResponseDTO createEvent(String tenantId, String country, CreateEventRequestDTO request,
                                        MultipartFile eventBanner) {
        return createEvent(tenantId, null, country, request, eventBanner);
    }

    @Transactional
    public EventResponseDTO createEvent(String tenantId, UUID tenantUserUuid, String country,
                                        CreateEventRequestDTO request, MultipartFile eventBanner) {
        log.info("Creating event tenantId={} tenantUserUuid={} country={} title={} venue={} startDateTime={} capacity={} hasBanner={}",
                tenantId, tenantUserUuid, country, request.getTitle(), request.getVenue(), request.getStartDateTime(),
                request.getTotalCapacity(), eventBanner != null && !eventBanner.isEmpty());

        // Country is stamped from the organizer's JWT, never the request body.
        // A token minted before country was captured won't carry the claim;
        // reject rather than persist an event with no country.
        if (country == null || country.isBlank()) {
            log.warn("Event create rejected, missing country claim on token tenantId={}", tenantId);
            throw new BadRequestException("Your session is missing country information. Please sign in again.");
        }

        if (eventRepository.existsByTenantIdAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                tenantId, request.getTitle(), request.getVenue(), request.getStartDateTime())) {
            log.warn("Event create rejected, duplicate tenantId={} title={} venue={} startDateTime={}",
                    tenantId, request.getTitle(), request.getVenue(), request.getStartDateTime());
            throw new ConflictException("An event with the same title, venue and date already exists");
        }

        Event event = Event.builder()
                .tenantId(tenantId)
                // Dual-write: stamp both the legacy email pointer and the
                // stable user_uuid identifier so new rows are correct from
                // day one. tenantUserUuid is null only for code paths that
                // haven't been migrated to extract it from the JWT yet
                // (legacy tests via the 3-arg overload); the backfill
                // runner picks those up on the next startup.
                .tenantUserUuid(tenantUserUuid)
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
        log.info("Event created eventId={} tenantId={} tenantUserUuid={}",
                saved.getEventId(), tenantId, saved.getTenantUserUuid());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional(readOnly = true)
    public BannerImage getEventBanner(UUID eventId) {
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (event.getBannerImage() == null || event.getBannerImage().length == 0) {
            throw new NotFoundException("No banner image has been uploaded for this event yet.");
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
            throw new BadRequestException("That image is too large. Please use one under 5 MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_BANNER_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BadRequestException("Please upload a JPG, PNG, GIF, or WEBP image.");
        }
        try {
            event.setBannerImage(file.getBytes());
            event.setBannerContentType(contentType.toLowerCase());
        } catch (IOException e) {
            // Genuine server-side I/O failure reading the upload stream — let
            // the catch-all in GlobalExceptionHandler return 500 with a
            // sanitised message. We wrap so the IOException doesn't escape
            // the @Transactional boundary unchecked.
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
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can update only own event;
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized update attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new ForbiddenException("You are not authorized to update this event");
        }

        // Capture pre-patch values so we can tell whether a customer-affecting
        // field (start time / venue) actually changed. Only those trigger an
        // attendee notification — a description/category/capacity tweak does not.
        java.time.LocalDateTime oldStartDateTime = event.getStartDateTime();
        String oldVenue = event.getVenue();

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
            throw new BadRequestException("The event end time must be after the start time.");
        }
        if (request.getTotalCapacity() != null) {
            int diff = request.getTotalCapacity() - event.getTotalCapacity();
            event.setTotalCapacity(request.getTotalCapacity());
            event.setAvailableTickets(event.getAvailableTickets() + diff);
        }

        Event saved = eventRepository.save(event);
        log.info("Event updated eventId={} tenantId={} title={} venue={} startDateTime={} endDateTime={}",
                eventId, tenantId, saved.getTitle(), saved.getVenue(), saved.getStartDateTime(), saved.getEndDateTime());

        // Notify confirmed attendees only when the start time or venue moved —
        // best-effort, never blocks the update (the gateway swallows failures).
        boolean startChanged = request.getStartDateTime() != null
                && !java.util.Objects.equals(oldStartDateTime, saved.getStartDateTime());
        boolean venueChanged = request.getVenue() != null
                && !java.util.Objects.equals(oldVenue, saved.getVenue());
        if (startChanged || venueChanged) {
            bookingNotificationGateway.notifyEventChange(
                    saved.getEventId(),
                    BookingNotificationGateway.CHANGE_UPDATED,
                    saved.getTitle(),
                    startChanged ? String.valueOf(saved.getStartDateTime()) : null,
                    venueChanged ? saved.getVenue() : null);
        }

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
            throw new BadRequestException("count must be positive");
        }
        log.info("Consuming availability eventId={} count={}", eventId, count);
        int updated = eventRepository.decrementAvailableTickets(eventId, count);
        if (updated == 0) {
            // Either the event doesn't exist or available < count.
            Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                    .orElseThrow(() -> new NotFoundException("Event not found"));
            throw new ConflictException("Insufficient availability: requested=" + count
                    + " available=" + event.getAvailableTickets());
        }
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        log.info("Availability consumed eventId={} count={} remaining={}",
                eventId, count, event.getAvailableTickets());
        return event.getAvailableTickets();
    }

    // Internal: called by booking-service when a confirmed booking is
    // reversed (admin refund, no-show, real-payment failure compensation)
    // so the seats it consumed return to the available pool. Returns the
    // new availableTickets value. Clamped to totalCapacity at the SQL
    // level — a buggy or replayed release can't push available above the
    // event's seat count. Callers should guard against double-release with
    // a per-booking idempotency flag (see Booking.availability_released);
    // hitting an over-cap clamp throws here so the caller knows to inspect.
    @Transactional
    public int releaseAvailability(UUID eventId, int count) {
        if (count <= 0) {
            throw new BadRequestException("count must be positive");
        }
        log.info("Releasing availability eventId={} count={}", eventId, count);
        int updated = eventRepository.releaseAvailableTickets(eventId, count);
        if (updated == 0) {
            Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                    .orElseThrow(() -> new NotFoundException("Event not found"));
            throw new ConflictException("Cannot release " + count + " ticket(s): would exceed totalCapacity"
                    + " available=" + event.getAvailableTickets()
                    + " total=" + event.getTotalCapacity());
        }
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        log.info("Availability released eventId={} count={} remaining={}",
                eventId, count, event.getAvailableTickets());
        return event.getAvailableTickets();
    }

    @Transactional
    public EventResponseDTO activateEvent(String tenantId, String role, UUID eventId) {
        log.info("Activating event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Activate failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized activate attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new ForbiddenException("You are not authorized to activate this event");
        }

        // An admin-rejected event cannot be (re)published — not even by its
        // owner — until a SUPER_ADMIN approves it (PUT /events/{id}/approve).
        // This keeps the invariant active=true => rejected=false, which is what
        // lets the inactive listing reliably surface every rejected event.
        if (event.isRejected()) {
            log.warn("Activate refused, event is admin-rejected eventId={} tenantId={}", eventId, tenantId);
            throw new ConflictException("This event has been rejected by an administrator and cannot be activated");
        }

        event.setActive(true);
        Event saved = eventRepository.save(event);
        log.info("Event activated eventId={} tenantId={}", eventId, tenantId);
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional
    public EventResponseDTO deactivateEvent(String tenantId, String role, UUID eventId) {
        log.info("Deactivating event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Deactivate failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized deactivate attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new ForbiddenException("You are not authorized to deactivate this event");
        }

        event.setActive(false);
        Event saved = eventRepository.save(event);
        log.info("Event deactivated eventId={} tenantId={}", eventId, tenantId);
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    /**
     * Admin moderation: flag an event as rejected so it is removed from every
     * public bookable listing. Also flips {@code active=false} so the event
     * drops into the inactive listing (where an admin can still find it) and so
     * the invariant {@code active=true => rejected=false} holds. SUPER_ADMIN-gated
     * at the controller; acts on any tenant's event by design.
     */
    @Transactional
    public EventResponseDTO rejectEvent(UUID eventId) {
        log.info("Rejecting event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Reject failed, event not found eventId={}", eventId);
                    return new NotFoundException("Event not found");
                });
        event.setRejected(true);
        event.setActive(false);
        Event saved = eventRepository.save(event);
        log.info("Event rejected eventId={} tenantId={}", eventId, saved.getTenantId());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    /**
     * Admin moderation: clear a previous rejection. Leaves {@code active=false}
     * — the owning organizer re-publishes via PUT /events/{id}/activate when
     * ready (now permitted again since rejected is false). SUPER_ADMIN-gated at
     * the controller.
     */
    @Transactional
    public EventResponseDTO approveEvent(UUID eventId) {
        log.info("Approving (un-rejecting) event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Approve failed, event not found eventId={}", eventId);
                    return new NotFoundException("Event not found");
                });
        event.setRejected(false);
        Event saved = eventRepository.save(event);
        log.info("Event approved eventId={} tenantId={}", eventId, saved.getTenantId());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional
    public void deleteEvent(String tenantId, String role, UUID eventId) {
        log.info("Deleting event eventId={} tenantId={} role={}", eventId, tenantId, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Delete failed, event not found eventId={} tenantId={}", eventId, tenantId);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can delete only own event;
        if (!isAdmin && !event.getTenantId().equals(tenantId)) {
            log.warn("Unauthorized delete attempt eventId={} tenantId={} ownerTenantId={}",
                    eventId, tenantId, event.getTenantId());
            throw new ForbiddenException("You are not authorized to delete this event");
        }

        event.setDeleted(true);
        eventRepository.save(event);
        log.info("Event deleted (soft) eventId={} tenantId={}", eventId, tenantId);

        // A soft-delete is a cancellation — tell confirmed attendees. Best-effort.
        bookingNotificationGateway.notifyEventChange(
                event.getEventId(),
                BookingNotificationGateway.CHANGE_CANCELLED,
                event.getTitle(),
                null, null);
    }

    public void deleteEvent(String tenantId, UUID eventId) {
        deleteEvent(tenantId, "ROLE_EVENT_ORGANIZER", eventId);
    }

}
