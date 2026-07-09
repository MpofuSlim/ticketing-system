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
import com.innbucks.eventservice.util.HtmlSanitizer;
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
    // GIF is deliberately NOT accepted: banners are static marketing images and
    // animated GIFs are rejected as a product decision — don't re-add it here
    // without also restoring the GIF magic-byte branch in isSupportedImageSignature.
    private static final Set<String> ALLOWED_BANNER_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

    // OWASP A03: allowlist of sortable properties = exactly the persistent
    // fields of the Event entity. A sortBy outside this set is rejected with a
    // clean 400 instead of leaking a 500 PropertyReferenceException (which also
    // enables entity field-name enumeration). Every property sortable today
    // stays sortable, so this is invisible to legitimate callers.
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "eventId", "tenantUserUuid", "title", "description", "venue", "country",
            "category", "location", "startDateTime", "endDateTime", "totalCapacity",
            "availableTickets", "bannerImage", "bannerContentType", "version",
            "deleted", "active", "rejected", "createdAt", "updatedAt"
    );

    // Matches the controllers' @RequestParam(defaultValue = "startDateTime").
    // Used when sortBy arrives null/blank (e.g. an explicit "?sortBy=") so a
    // null/blank property is never handed to Sort.by.
    private static final String DEFAULT_SORT_FIELD = "startDateTime";

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
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching active events from={} to={} venue={} page={} size={} sortBy={}",
                from, to, venue, page, size, sortBy);
        // Published events only for this anonymous listing: active=true, not
        // admin-rejected, not ended. Drafts / admin-rejected events must never
        // surface publicly — organizers see their own via GET /events/my.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return stripInternalIds(enrichWithAvailability(
                eventRepository.findAllActiveOnly(from, to, venue, null, now, pageable)));
    }

    /** Nulls organizer-internal identifiers before a response leaves an
     *  anonymous-reachable endpoint. {@code tenantUserUuid} is the organizer's
     *  stable cross-service user id (used fleet-wide for ownership checks); it
     *  must not be broadcast to the public, where it enables organizer
     *  enumeration/correlation. Authenticated owner/admin listings keep it. */
    private Page<EventResponseDTO> stripInternalIds(Page<EventResponseDTO> page) {
        page.getContent().forEach(dto -> dto.setTenantUserUuid(null));
        return page;
    }

    private static EventResponseDTO stripInternalIds(EventResponseDTO dto) {
        dto.setTenantUserUuid(null);
        return dto;
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
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching active=true events from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                from, to, venue, country, category, page, size, sortBy);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<Event> events = category == null
                ? eventRepository.findAllActiveOnly(from, to, venue, country, now, pageable)
                : eventRepository.findAllActiveOnlyByCategory(from, to, venue, country, category, now, pageable);
        return stripInternalIds(enrichWithAvailability(events));
    }

    /**
     * List the events owned by a single organizer. SUPER_ADMIN should call
     * {@link #getAllActiveEvents} instead — they see everything. Keyed on
     * the stable {@code tenantUserUuid} (was the organizer's email under
     * the old tenant-id pattern); pre-V6 rows whose {@code tenant_user_uuid}
     * hasn't been backfilled yet are invisible here until the runner
     * catches them up.
     */
    public Page<EventResponseDTO> getMyEvents(
            UUID tenantUserUuid,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching events for tenantUserUuid={} from={} to={} venue={} page={} size={} sortBy={}",
                tenantUserUuid, from, to, venue, page, size, sortBy);
        return enrichWithAvailability(
                eventRepository.findByTenantUserUuid(tenantUserUuid, from, to, venue, pageable));
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
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
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
            UUID tenantUserUuid,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching active events for tenantUserUuid={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                tenantUserUuid, from, to, venue, country, category, page, size, sortBy);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<Event> events = category == null
                ? eventRepository.findByTenantUserUuidActiveOnly(tenantUserUuid, from, to, venue, country, now, pageable)
                : eventRepository.findByTenantUserUuidActiveOnlyByCategory(tenantUserUuid, from, to, venue, country, category, now, pageable);
        return enrichWithAvailability(events);
    }

    /**
     * Team-member view of {@code /events/active}: the {@code active=true}
     * counterpart of {@link #getMyAssignedEvents}. Returns only the events the
     * member is explicitly assigned to (deny-by-default) that are also flagged
     * active. The {@code organizerUuid} (parent organizer's uuid from the JWT)
     * is enforced as a defence-in-depth ownership filter so a stale assignment
     * row pointing at a foreign organizer's event can never leak through.
     *
     * <p>An empty {@code assignedEventIds} short-circuits to an empty page —
     * Spring JPA forbids an empty {@code IN (...)} list in some dialects, and a
     * "no events" answer is the correct semantic in deny-by-default mode.
     */
    public Page<EventResponseDTO> getMyAssignedActiveEvents(
            UUID organizerUuid,
            java.util.Collection<UUID> assignedEventIds,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        if (assignedEventIds == null || assignedEventIds.isEmpty()) {
            log.debug("Team-member has no event assignments organizerUuid={} -> empty active page", organizerUuid);
            return Page.empty(pageable);
        }
        log.debug("Fetching assigned active events for team-member organizerUuid={} assignedCount={} country={} category={} page={} size={}",
                organizerUuid, assignedEventIds.size(), country, category, page, size);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Page<Event> events = category == null
                ? eventRepository.findByTenantUserUuidActiveOnlyAndEventIdIn(organizerUuid, assignedEventIds, from, to, venue, country, now, pageable)
                : eventRepository.findByTenantUserUuidActiveOnlyByCategoryAndEventIdIn(organizerUuid, assignedEventIds, from, to, venue, country, category, now, pageable);
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
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching active=false events from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                from, to, venue, country, category, page, size, sortBy);
        Page<Event> events = category == null
                ? eventRepository.findAllInactiveOnly(from, to, venue, country, pageable)
                : eventRepository.findAllInactiveOnlyByCategory(from, to, venue, country, category, pageable);
        return enrichWithAvailability(events);
    }

    /**
     * Same as {@link #getInactiveOnlyEvents} but scoped to a single organizer,
     * so an EVENT_ORGANIZER hitting {@code /events/inactive} only sees their
     * own inactive events.
     */
    public Page<EventResponseDTO> getMyInactiveEvents(
            UUID tenantUserUuid,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Fetching inactive events for tenantUserUuid={} from={} to={} venue={} country={} category={} page={} size={} sortBy={}",
                tenantUserUuid, from, to, venue, country, category, page, size, sortBy);
        Page<Event> events = category == null
                ? eventRepository.findByTenantUserUuidInactiveOnly(tenantUserUuid, from, to, venue, country, pageable)
                : eventRepository.findByTenantUserUuidInactiveOnlyByCategory(tenantUserUuid, from, to, venue, country, category, pageable);
        return enrichWithAvailability(events);
    }

    /**
     * Team-member view of {@code /events/inactive}: the {@code active=false}
     * counterpart of {@link #getMyAssignedEvents}, mirroring
     * {@link #getMyInactiveEvents}. Returns only the inactive events the member
     * is explicitly assigned to (deny-by-default). The {@code organizerUuid}
     * (parent organizer's uuid from the JWT) is enforced as a defence-in-depth
     * ownership filter so a stale assignment row pointing at a foreign
     * organizer's event can never leak through.
     *
     * <p>An empty {@code assignedEventIds} short-circuits to an empty page —
     * Spring JPA forbids an empty {@code IN (...)} list in some dialects, and a
     * "no events" answer is the correct semantic in deny-by-default mode.
     */
    public Page<EventResponseDTO> getMyAssignedInactiveEvents(
            UUID organizerUuid,
            java.util.Collection<UUID> assignedEventIds,
            LocalDateTime from,
            LocalDateTime to,
            String venue,
            String country,
            EventCategory category,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        if (assignedEventIds == null || assignedEventIds.isEmpty()) {
            log.debug("Team-member has no event assignments organizerUuid={} -> empty inactive page", organizerUuid);
            return Page.empty(pageable);
        }
        log.debug("Fetching assigned inactive events for team-member organizerUuid={} assignedCount={} country={} category={} page={} size={}",
                organizerUuid, assignedEventIds.size(), country, category, page, size);
        Page<Event> events = category == null
                ? eventRepository.findByTenantUserUuidInactiveOnlyAndEventIdIn(organizerUuid, assignedEventIds, from, to, venue, country, pageable)
                : eventRepository.findByTenantUserUuidInactiveOnlyByCategoryAndEventIdIn(organizerUuid, assignedEventIds, from, to, venue, country, category, pageable);
        return enrichWithAvailability(events);
    }

    public EventResponseDTO getEventById(UUID eventId) {
        log.debug("Fetching event eventId={}", eventId);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Event not found eventId={}", eventId);
                    return new NotFoundException("Event not found");
                });
        // Published events are visible to anyone by UUID. A draft (active=false,
        // e.g. freshly created and pending approval) or admin-rejected event is
        // served ONLY to its owning organizer or a SUPER_ADMIN — anyone else,
        // including anonymous callers, gets 404 so it can't be read by guessing/
        // harvesting the UUID.
        boolean owner = callerMayViewUnpublished(event);
        boolean published = event.isActive() && !event.isRejected();
        if (!published && !owner) {
            log.warn("Rejecting access to non-published event eventId={} active={} rejected={}",
                    eventId, event.isActive(), event.isRejected());
            throw new NotFoundException("Event not found");
        }
        EventResponseDTO response = toDtoWithAvailability(event, fetchActiveCounts(eventId));
        attachOrganizer(response, event, resolveOrganizers(List.of(event)));
        response.setSeatCategories(seatCategoryGateway.fetchForEvent(eventId));
        // Keep the organizer's internal id for the owner/admin who act on it;
        // strip it for anonymous/other-organizer (public) consumption.
        return owner ? response : stripInternalIds(response);
    }

    /**
     * Whether the current caller may view an UNPUBLISHED (draft or admin-rejected)
     * event: the owning organizer (JWT {@code organizerUuid} == the event's
     * {@code tenantUserUuid}) or a SUPER_ADMIN. Anonymous callers and other
     * organizers may not — the public by-id / banner endpoints 404 for them.
     */
    private boolean callerMayViewUnpublished(Event event) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        boolean superAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        if (superAdmin) {
            return true;
        }
        UUID caller = com.innbucks.eventservice.security.AuthenticatedCaller.organizerUuid(auth);
        return caller != null && caller.equals(event.getTenantUserUuid());
    }

    public Page<EventResponseDTO> searchEvents(
            String q,
            int page,
            int size,
            String sortBy
    ) {
        Pageable pageable = PageRequest.of(page, size, ascendingSort(sortBy));
        log.debug("Searching events q={} page={} size={} sortBy={}", q, page, size, sortBy);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return stripInternalIds(enrichWithAvailability(
                eventRepository.searchByKeyword(q == null ? "" : q.trim(), now, pageable)));
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
        Map<UUID, OrganizerDTO> organizers = resolveOrganizers(entities.getContent());

        List<EventResponseDTO> dtos = new ArrayList<>(entities.getNumberOfElements());
        int n = 1;
        for (Event event : entities.getContent()) {
            EventResponseDTO dto = toDtoWithAvailability(event, activeCounts);
            attachOrganizer(dto, event, organizers);
            dto.setEventNo(n++);
            dtos.add(dto);
        }
        return stripInternalIds(new PageImpl<>(dtos, pageable, entities.getTotalElements()));
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
        Map<UUID, OrganizerDTO> organizers = resolveOrganizers(content);
        return page.map(e -> {
            EventResponseDTO dto = toDtoWithAvailability(e, finalCounts);
            attachOrganizer(dto, e, organizers);
            return dto;
        });
    }

    // Batch-resolve organizer (tenant) business details from user-service for
    // the events on this page. Keyed on the stable {@code tenantUserUuid} —
    // events whose uuid hasn't been backfilled yet are simply skipped (their
    // response will have a null organizer until the runner catches up).
    // Returns an empty map if user-service is unreachable (the gateway's
    // circuit breaker falls back) so listings still serve — just without
    // organizer details.
    private Map<UUID, OrganizerDTO> resolveOrganizers(Collection<Event> events) {
        List<UUID> tenantUserUuids = events.stream()
                .map(Event::getTenantUserUuid)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, OrganizerDTO> organizers = organizerGateway.organizersByUserUuids(tenantUserUuids);
        return organizers == null ? Collections.emptyMap() : organizers;
    }

    private void attachOrganizer(EventResponseDTO dto, Event event, Map<UUID, OrganizerDTO> organizers) {
        if (dto != null && organizers != null && event.getTenantUserUuid() != null) {
            dto.setOrganizer(organizers.get(event.getTenantUserUuid()));
        }
    }

    private Map<UUID, Long> fetchActiveCounts(UUID eventId) {
        if (eventId == null) {
            return java.util.Collections.emptyMap();
        }
        Map<UUID, Long> counts = bookingGateway.activeCountsByEventIds(List.of(eventId));
        return counts == null ? java.util.Collections.emptyMap() : counts;
    }

    public EventResponseDTO createEvent(UUID tenantUserUuid, String country, CreateEventRequestDTO request) {
        return createEvent(tenantUserUuid, country, request, null);
    }

    @Transactional
    public EventResponseDTO createEvent(UUID tenantUserUuid, String country,
                                        CreateEventRequestDTO request, MultipartFile eventBanner) {
        log.info("Creating event tenantUserUuid={} country={} title={} venue={} startDateTime={} capacity={} hasBanner={}",
                tenantUserUuid, country, request.getTitle(), request.getVenue(), request.getStartDateTime(),
                request.getTotalCapacity(), eventBanner != null && !eventBanner.isEmpty());

        if (tenantUserUuid == null) {
            // Defence in depth — the controller resolves this from the JWT and
            // the JwtFilter rejects pre-V20 tokens that lack the claim, so we
            // should never see null here on a healthy auth path.
            log.warn("Event create rejected, missing organizerUuid on token");
            throw new BadRequestException("Your session is missing organizer information. Please sign in again.");
        }

        // Country is stamped from the organizer's JWT, never the request body.
        // A token minted before country was captured won't carry the claim;
        // reject rather than persist an event with no country.
        if (country == null || country.isBlank()) {
            log.warn("Event create rejected, missing country claim on token tenantUserUuid={}", tenantUserUuid);
            throw new BadRequestException("Your session is missing country information. Please sign in again.");
        }

        if (eventRepository.existsByTenantUserUuidAndTitleAndVenueAndStartDateTimeAndDeletedFalse(
                tenantUserUuid, request.getTitle(), request.getVenue(), request.getStartDateTime())) {
            log.warn("Event create rejected, duplicate tenantUserUuid={} title={} venue={} startDateTime={}",
                    tenantUserUuid, request.getTitle(), request.getVenue(), request.getStartDateTime());
            throw new ConflictException("An event with the same title, venue and date already exists");
        }

        Event event = Event.builder()
                .tenantUserUuid(tenantUserUuid)
                // OWASP A03: sanitize free text on the write path so stored data
                // is safe regardless of how any client renders it.
                .title(HtmlSanitizer.stripAll(request.getTitle()))
                .description(HtmlSanitizer.sanitizeRichText(request.getDescription()))
                .venue(HtmlSanitizer.stripAll(request.getVenue()))
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
        log.info("Event created eventId={} tenantUserUuid={}",
                saved.getEventId(), saved.getTenantUserUuid());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional(readOnly = true)
    public BannerImage getEventBanner(UUID eventId) {
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        // Same visibility rule as getEventById: a draft/rejected event's banner is
        // owner/admin-only; anonymous callers get 404 so the artwork of an
        // unpublished event isn't exposed by UUID.
        boolean published = event.isActive() && !event.isRejected();
        if (!published && !callerMayViewUnpublished(event)) {
            throw new NotFoundException("Event not found");
        }
        if (event.getBannerImage() == null || event.getBannerImage().length == 0) {
            throw new NotFoundException("No banner image has been uploaded for this event yet.");
        }
        return new BannerImage(event.getBannerImage(), event.getBannerContentType());
    }

    public record BannerImage(byte[] bytes, String contentType) {}

    // OWASP A03: validate the user-supplied sort property against the allowlist
    // before handing it to Spring Data. A null/blank sortBy falls back to the
    // default sort field (matching the controllers' @RequestParam default); any
    // non-blank value outside the allowlist is a clean 400 rather than a 500
    // PropertyReferenceException (which also leaks entity field names).
    private static Sort ascendingSort(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return Sort.by(Sort.Direction.ASC, DEFAULT_SORT_FIELD);
        }
        if (!SORTABLE_FIELDS.contains(sortBy)) {
            throw new BadRequestException("Invalid sort field: " + sortBy);
        }
        return Sort.by(Sort.Direction.ASC, sortBy);
    }

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
            throw new BadRequestException("Please upload a JPG, PNG, or WEBP image.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // Genuine server-side I/O failure reading the upload stream — let
            // the catch-all in GlobalExceptionHandler return 500 with a
            // sanitised message. We wrap so the IOException doesn't escape
            // the @Transactional boundary unchecked.
            throw new RuntimeException("Failed to read banner image", e);
        }
        // OWASP A03: the declared Content-Type is attacker-controlled, so confirm
        // the payload really is one of the image formats we accept by matching its
        // magic-byte signature before we store (and later serve) it — this rejects
        // an HTML/script payload smuggled under an image/* header.
        if (!isSupportedImageSignature(bytes)) {
            throw new BadRequestException("Please upload a valid image file (JPG, PNG, or WEBP).");
        }
        event.setBannerImage(bytes);
        event.setBannerContentType(contentType.toLowerCase());
    }

    // Magic-byte sniff for the three banner formats we allow (GIF is rejected —
    // see ALLOWED_BANNER_CONTENT_TYPES). Signature-only (no full image decode) —
    // enough to reject non-image payloads without pulling in an image library.
    private static boolean isSupportedImageSignature(byte[] b) {
        if (b == null) {
            return false;
        }
        // JPEG: FF D8 FF
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E
                && (b[3] & 0xFF) == 0x47 && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return true;
        }
        // WEBP: bytes 0-3 "RIFF" (52 49 46 46) AND bytes 8-11 "WEBP" (57 45 42 50)
        if (b.length >= 12
                && (b[0] & 0xFF) == 0x52 && (b[1] & 0xFF) == 0x49 && (b[2] & 0xFF) == 0x46
                && (b[3] & 0xFF) == 0x46
                && (b[8] & 0xFF) == 0x57 && (b[9] & 0xFF) == 0x45 && (b[10] & 0xFF) == 0x42
                && (b[11] & 0xFF) == 0x50) {
            return true;
        }
        return false;
    }

    @Transactional
    public EventResponseDTO updateEvent(UUID tenantUserUuid, String role, UUID eventId, UpdateEventRequestDTO request) {
        log.info("Updating event eventId={} tenantUserUuid={} role={} req.title={} req.venue={} req.startDateTime={} req.endDateTime={} req.totalCapacity={}",
                eventId, tenantUserUuid, role,
                request.getTitle(), request.getVenue(), request.getStartDateTime(), request.getEndDateTime(), request.getTotalCapacity());
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Update failed, event not found eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can update only own event. Pre-V6 rows without a
        // tenantUserUuid fail closed (admin must update via SUPER_ADMIN role
        // until backfill catches them up) — safer than silently allowing the
        // first organizer through.
        if (!isAdmin && !java.util.Objects.equals(event.getTenantUserUuid(), tenantUserUuid)) {
            log.warn("Unauthorized update attempt eventId={} tenantUserUuid={} ownerTenantUserUuid={}",
                    eventId, tenantUserUuid, event.getTenantUserUuid());
            throw new ForbiddenException("You are not authorized to update this event");
        }

        // Capture pre-patch values so we can tell whether a customer-affecting
        // field (start time / venue) actually changed. Only those trigger an
        // attendee notification — a description/category/capacity tweak does not.
        java.time.LocalDateTime oldStartDateTime = event.getStartDateTime();
        String oldVenue = event.getVenue();

        // OWASP A03: sanitize free text on the write path (title/venue plain-text,
        // description rich-text) before it lands on the entity. category is an
        // enum — no free text to sanitize.
        if (request.getTitle() != null)        event.setTitle(HtmlSanitizer.stripAll(request.getTitle()));
        if (request.getDescription() != null)  event.setDescription(HtmlSanitizer.sanitizeRichText(request.getDescription()));
        if (request.getVenue() != null)        event.setVenue(HtmlSanitizer.stripAll(request.getVenue()));
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
        log.info("Event updated eventId={} tenantUserUuid={} title={} venue={} startDateTime={} endDateTime={}",
                eventId, tenantUserUuid, saved.getTitle(), saved.getVenue(), saved.getStartDateTime(), saved.getEndDateTime());

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

    public EventResponseDTO updateEvent(UUID tenantUserUuid, UUID eventId, UpdateEventRequestDTO request) {
        return updateEvent(tenantUserUuid, "ROLE_EVENT_ORGANIZER", eventId, request);
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
    public EventResponseDTO activateEvent(UUID tenantUserUuid, String role, UUID eventId) {
        log.info("Activating event eventId={} tenantUserUuid={} role={}", eventId, tenantUserUuid, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Activate failed, event not found eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);
        if (!isAdmin && !java.util.Objects.equals(event.getTenantUserUuid(), tenantUserUuid)) {
            log.warn("Unauthorized activate attempt eventId={} tenantUserUuid={} ownerTenantUserUuid={}",
                    eventId, tenantUserUuid, event.getTenantUserUuid());
            throw new ForbiddenException("You are not authorized to activate this event");
        }

        // An admin-rejected event cannot be (re)published — not even by its
        // owner — until a SUPER_ADMIN approves it (PUT /events/{id}/approve).
        // This keeps the invariant active=true => rejected=false, which is what
        // lets the inactive listing reliably surface every rejected event.
        if (event.isRejected()) {
            log.warn("Activate refused, event is admin-rejected eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
            throw new ConflictException("This event has been rejected by an administrator and cannot be activated");
        }

        event.setActive(true);
        Event saved = eventRepository.save(event);
        log.info("Event activated eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional
    public EventResponseDTO deactivateEvent(UUID tenantUserUuid, String role, UUID eventId) {
        log.info("Deactivating event eventId={} tenantUserUuid={} role={}", eventId, tenantUserUuid, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Deactivate failed, event not found eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);
        if (!isAdmin && !java.util.Objects.equals(event.getTenantUserUuid(), tenantUserUuid)) {
            log.warn("Unauthorized deactivate attempt eventId={} tenantUserUuid={} ownerTenantUserUuid={}",
                    eventId, tenantUserUuid, event.getTenantUserUuid());
            throw new ForbiddenException("You are not authorized to deactivate this event");
        }

        event.setActive(false);
        Event saved = eventRepository.save(event);
        log.info("Event deactivated eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
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
        log.info("Event rejected eventId={} tenantUserUuid={}", eventId, saved.getTenantUserUuid());
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
        log.info("Event approved eventId={} tenantUserUuid={}", eventId, saved.getTenantUserUuid());
        return toDtoWithAvailability(saved, fetchActiveCounts(saved.getEventId()));
    }

    @Transactional
    public void deleteEvent(UUID tenantUserUuid, String role, UUID eventId) {
        log.info("Deleting event eventId={} tenantUserUuid={} role={}", eventId, tenantUserUuid, role);
        Event event = eventRepository.findByEventIdAndDeletedFalse(eventId)
                .orElseThrow(() -> {
                    log.warn("Delete failed, event not found eventId={} tenantUserUuid={}", eventId, tenantUserUuid);
                    return new NotFoundException("Event not found");
                });

        boolean isAdmin = "ROLE_SUPER_ADMIN".equals(role);

        // EVENT_ORGANIZER can delete only own event;
        if (!isAdmin && !java.util.Objects.equals(event.getTenantUserUuid(), tenantUserUuid)) {
            log.warn("Unauthorized delete attempt eventId={} tenantUserUuid={} ownerTenantUserUuid={}",
                    eventId, tenantUserUuid, event.getTenantUserUuid());
            throw new ForbiddenException("You are not authorized to delete this event");
        }

        event.setDeleted(true);
        eventRepository.save(event);
        log.info("Event deleted (soft) eventId={} tenantUserUuid={}", eventId, tenantUserUuid);

        // A soft-delete is a cancellation — tell confirmed attendees. Best-effort.
        bookingNotificationGateway.notifyEventChange(
                event.getEventId(),
                BookingNotificationGateway.CHANGE_CANCELLED,
                event.getTitle(),
                null, null);
    }

    public void deleteEvent(UUID tenantUserUuid, UUID eventId) {
        deleteEvent(tenantUserUuid, "ROLE_EVENT_ORGANIZER", eventId);
    }

}
