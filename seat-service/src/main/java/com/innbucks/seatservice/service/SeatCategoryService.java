package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.*;
import com.innbucks.seatservice.entity.*;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.exception.NotFoundException;
import com.innbucks.seatservice.exception.ServiceUnavailableException;
import com.innbucks.seatservice.repository.*;
import com.innbucks.seatservice.util.HtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
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

    /**
     * The phases of {@link #updateCategory}. A setter so the plain-{@code new}
     * unit tests keep their construction; Spring always calls it, and with none
     * set the phases run inline.
     */
    private TransactionTemplate readTx;
    private TransactionTemplate writeTx;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.readTx = TransactionPhases.readOnly(transactionManager);
        this.writeTx = TransactionPhases.readWrite(transactionManager);
    }

    // createCategory and deleteCategory still make their event-service /
    // booking-service calls inside their transaction, ON PURPOSE: those calls
    // are the fail-closed oversell guard (requireCapacityHeadroom) and the
    // delete guard (requireNoActiveBookings), whose answers decide the write,
    // and the oversell guard sums the event's live categories in the SAME
    // transaction that inserts the new one. Splitting them would also move the
    // remote refusal (503) ahead of the local ones (duplicate name, price, seat
    // cap), changing which error a caller sees. They hold no row lock during
    // the call (every write comes after it) — only the connection.
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

        // Defence-in-depth on top of @DecimalMin(inclusive=false) at the DTO:
        // the bean-validation guard only fires on @Valid-bound controller calls,
        // so service-layer / S2S callers don't ride on it. Same shape as the
        // totalSeats cap check below.
        if (request.getPrice() == null || request.getPrice().signum() <= 0) {
            log.warn("Category creation rejected, non-positive price eventId={} name={} price={}",
                    request.getEventId(), request.getName(), request.getPrice());
            throw new BadRequestException("Price must be greater than 0.");
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

        requireCapacityHeadroom(request.getEventId(), totalSeats, authHeader);

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
                .name(HtmlSanitizer.stripAll(request.getName()))
                .description(HtmlSanitizer.stripAll(request.getDescription()))
                .price(request.getPrice())
                .totalSeats(totalSeats)
                .availableSeats(totalSeats)
                .deleted(false)
                .build();

        categoryRepository.save(category);

        // Auto-generate seats with per-section capacity, e.g. A1-A5, B1-B7, C1-C8
        List<Seat> seats = new ArrayList<>();
        for (SectionSeatConfigDTO sectionConfig : request.getSections()) {
            String sectionLabel = HtmlSanitizer.stripAll(sectionConfig.getSection().trim().toUpperCase(Locale.ROOT));
            // Optional per-section image, stamped on every seat in the section so
            // the read paths can recover it by section. Blank → null (no image);
            // non-blank is validated to an absolute http(s) URL at ingest.
            String sectionImageUrl = validateAndNormalizeImageUrl(sectionConfig.getImageUrl());
            for (int num = 1; num <= sectionConfig.getSeatCount(); num++) {
                seats.add(Seat.builder()
                        .category(category)
                        .sectionLabel(sectionLabel)
                        .sectionImageUrl(sectionImageUrl)
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

    public CreateCategoryResponseDTO updateCategory(UUID categoryId, UpdateCategoryRequestDTO request) {
        return updateCategory(categoryId, request, null, null, true, null);
    }

    /**
     * Update the editable metadata (name, description, price) of a category.
     * Seat layout and event are immutable here — see {@link UpdateCategoryRequestDTO}.
     * Mirrors {@code deleteCategory}'s auth shape: SUPER_ADMIN passes straight
     * through, an EVENT_ORGANIZER must own the category's event.
     *
     * <p><b>Deliberately NOT {@code @Transactional}: no remote call is made
     * while a transaction is open.</b> It used to hold one across both of its
     * remote calls — the event-service ownership lookup, and, after the write,
     * the booking-service live-count fetch for the response (which ran with the
     * category's UPDATE pending and the connection held). Now:
     * <ol>
     *   <li>an organizer's call reads the category in a short read-only
     *       transaction and asks event-service with none open. The event a
     *       category belongs to never changes, so the ownership answer still
     *       holds at the write; the guard stays fail-closed (403 when it cannot
     *       be verified) and keeps its place in the order of refusals;</li>
     *   <li>one write transaction re-reads the category (404 if it was deleted
     *       meanwhile, as it would have been), validates, saves, and rebuilds the
     *       sections from the seats table;</li>
     *   <li>the live count for the response is fetched after that commit. It
     *       decides nothing — it only renders {@code availableSeats}, and it
     *       already degraded to the stored mirror when booking-service was down.</li>
     * </ol>
     */
    public CreateCategoryResponseDTO updateCategory(UUID categoryId,
                                                    UpdateCategoryRequestDTO request,
                                                    UUID callerOrganizerUuid,
                                                    String requesterEmail,
                                                    boolean isAdmin,
                                                    String authHeader) {
        if (!isAdmin) {
            SeatCategory found = TransactionPhases.inTransaction(readTx, () -> loadLiveForUpdate(categoryId));
            requireEventOwnership(found.getEventId(), callerOrganizerUuid, requesterEmail, authHeader);
        }

        record Updated(SeatCategory category, List<SectionSeatConfigDTO> sections) {
        }
        Updated updated = TransactionPhases.inTransaction(writeTx, () -> {
            SeatCategory category = loadLiveForUpdate(categoryId);

            // Defence-in-depth on top of the DTO @DecimalMin (only fires on @Valid
            // controller calls), same as createCategory.
            if (request.getPrice() == null || request.getPrice().signum() <= 0) {
                log.warn("Category update rejected, non-positive price categoryId={} price={}",
                        categoryId, request.getPrice());
                throw new BadRequestException("Price must be greater than 0.");
            }

            // Renaming onto a name another live category in the same event already
            // uses is a conflict; ...AndIdNot lets a no-op rename (same name) through.
            if (categoryRepository.existsByEventIdAndNameAndDeletedFalseAndIdNot(
                    category.getEventId(), request.getName(), categoryId)) {
                log.warn("Category update rejected, duplicate name eventId={} name={} categoryId={}",
                        category.getEventId(), request.getName(), categoryId);
                throw new ConflictException("Category '" + request.getName()
                        + "' already exists for this event");
            }

            log.info("Updating seat category categoryId={} eventId={} name={} requesterEmail={} isAdmin={}",
                    categoryId, category.getEventId(), request.getName(), requesterEmail, isAdmin);

            category.setName(HtmlSanitizer.stripAll(request.getName()));
            category.setDescription(HtmlSanitizer.stripAll(request.getDescription()));
            category.setPrice(request.getPrice());
            categoryRepository.save(category);

            // Return the same shape getCategoriesByEvent emits — sections rebuilt
            // from the persisted seats (unchanged by this edit).
            return new Updated(category, sectionsForCategory(categoryId));
        });

        // Live availability for the response, after the commit (degrades to the
        // stored mirror if booking-service is down).
        Map<UUID, Long> counts = bookingServiceClient
                .fetchActiveCountsByCategories(List.of(categoryId))
                .orElse(null);
        SeatCategory category = updated.category();
        log.info("Seat category updated categoryId={} eventId={}", categoryId, category.getEventId());
        return toCreateResponseDTO(category, updated.sections(), liveAvailableSeats(category, counts));
    }

    private SeatCategory loadLiveForUpdate(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> {
                    log.warn("Category update failed, not found categoryId={}", categoryId);
                    return new NotFoundException("Seat category not found");
                });
    }

    /** Trim a section image URL; treat blank/empty as "no image" (null). */
    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        String trimmed = imageUrl.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Trim + validate a section image URL on the WRITE path. Blank → null (no
     * image); a non-blank value MUST be an absolute {@code http(s)} URL with a
     * host, else a 400.
     *
     * <p>A03/A10: this value is stored verbatim and echoed straight back to
     * clients (and a future seat-map preview could dereference it), so a
     * {@code javascript:}/{@code data:} payload (stored-XSS fuel) or a
     * relative/scheme-relative value (open-redirect / SSRF-rebasing fuel) must
     * not survive ingest. Reads still go through the trim-only
     * {@link #normalizeImageUrl}, so any pre-existing row is never re-validated
     * on the way out — this stricter gate applies to new writes only.
     */
    private static String validateAndNormalizeImageUrl(String imageUrl) {
        String normalized = normalizeImageUrl(imageUrl);
        if (normalized == null) {
            return null;
        }
        final URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException badUri) {
            throw invalidSectionImageUrl();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean absoluteHttp = ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null && !host.isBlank();
        if (!absoluteHttp) {
            throw invalidSectionImageUrl();
        }
        return normalized;
    }

    private static BadRequestException invalidSectionImageUrl() {
        return new BadRequestException(
                "Section image URL must be an absolute http(s) URL (e.g. https://cdn.example.com/section.png).");
    }

    private List<SectionSeatConfigDTO> sectionsForCategory(UUID categoryId) {
        return sectionsByCategory(List.of(categoryId)).getOrDefault(categoryId, List.of());
    }

    /**
     * Per-category section layout (label, seat count, image), in creation
     * order, from ONE grouped query — no seat row is loaded. A category with
     * no seats is absent from the map. Counting here is what makes the public
     * listing's cost independent of how many seats an event has; it used to
     * load every one of them (up to 500,000 per category) to count them.
     */
    private Map<UUID, List<SectionSeatConfigDTO>> sectionsByCategory(List<UUID> categoryIds) {
        Map<UUID, List<SectionSeatConfigDTO>> sections = new LinkedHashMap<>();
        if (categoryIds.isEmpty()) {
            return sections;
        }
        for (SeatRepository.SectionCount row : seatRepository.countSections(categoryIds)) {
            SectionSeatConfigDTO dto = new SectionSeatConfigDTO();
            dto.setSection(row.getSectionLabel());
            dto.setSeatCount(row.getSeatCount() == null ? 0 : row.getSeatCount().intValue());
            dto.setImageUrl(row.getImageUrl());
            sections.computeIfAbsent(row.getCategoryId(), ignored -> new ArrayList<>()).add(dto);
        }
        return sections;
    }

    public List<CreateCategoryResponseDTO> getCategoriesByEvent(UUID eventId) {
        log.debug("Fetching seat categories eventId={}", eventId);
        List<SeatCategory> categories = categoryRepository.findByEventIdAndDeletedFalse(eventId);
        List<UUID> categoryIds = categories.stream()
                .map(SeatCategory::getId)
                .collect(Collectors.toList());

        Map<UUID, List<SectionSeatConfigDTO>> sectionsByCategory = sectionsByCategory(categoryIds);

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
        requireNoActiveBookings(category);
        category.setDeleted(true);
        categoryRepository.save(category);
        log.info("Seat category soft-deleted categoryId={} eventId={}", categoryId, category.getEventId());
    }

    /**
     * Refuses to delete a category that still has active bookings against it.
     *
     * <p><b>Why this had to exist.</b> The delete path checked event ownership
     * and nothing else, so an organizer could soft-delete a category holding
     * paid tickets. The rows survive — {@code booking_items} is not cascaded —
     * but the category they name is gone, which strands every holder: the
     * ticket references a category the event no longer lists.
     *
     * <p><b>"Active" means PENDING or CONFIRMED</b>, per booking-service's
     * {@code /bookings/internal/categories/active-counts}. CANCELLED bookings
     * are excluded, so a category whose every sale was refunded IS deletable —
     * which is the case an organizer actually needs.
     *
     * <p><b>This is deliberately NOT applied to a reprice.</b> A booking freezes
     * {@code BookingItem.priceAtBooking} at purchase time and every later read —
     * the ticket, the receipt, the organizer's revenue report — uses that stored
     * value, never the category's current price. So changing the price cannot
     * restate or invalidate a sold ticket; it only sets what the NEXT buyer
     * pays, which is ordinary early-bird/late-release pricing. Blocking it would
     * mean one sale locks a category's price for the life of the event.
     *
     * <p><b>Fails CLOSED, and that is the whole point.</b>
     * {@link BookingServiceClient#fetchActiveCountsByCategories} returns an
     * empty Optional on any failure, because its other caller renders public
     * availability and must degrade rather than 500. A guard that reused that
     * convention would read "booking-service is down" as "no bookings" and wave
     * the delete through at exactly the moment it cannot be checked. An
     * unanswerable question is refused (503), not assumed safe.
     */
    private void requireNoActiveBookings(SeatCategory category) {
        UUID categoryId = category.getId();
        Map<UUID, Long> counts = bookingServiceClient
                .fetchActiveCountsByCategories(List.of(categoryId))
                .orElseThrow(() -> {
                    log.warn("Category delete refused, booking-service unreachable categoryId={} eventId={}",
                            categoryId, category.getEventId());
                    return new ServiceUnavailableException(
                            "Cannot verify whether this category has bookings right now. "
                                    + "Please try again shortly.");
                });
        long active = counts.getOrDefault(categoryId, 0L);
        if (active > 0) {
            log.warn("Category delete refused, active bookings categoryId={} eventId={} activeBookings={}",
                    categoryId, category.getEventId(), active);
            throw new ConflictException(
                    "'" + category.getName() + "' has " + active + " active booking"
                            + (active == 1 ? "" : "s") + " and cannot be deleted. "
                            + "Cancel or refund them first.");
        }
    }

    /**
     * Looks up the event in event-service and throws AccessDeniedException if
     * the caller is not its owning organizer. SUPER_ADMIN callers are checked
     * at the controller layer and never reach here. Matches the caller's
     * {@code organizerUuid} JWT claim against the event's
     * {@code tenantUserUuid} (event-service V7 / PR #259 dropped the legacy
     * email-as-tenantId column the prior check compared against).
     */
    /**
     * Refuses a category that would allocate more seats than the event declares.
     *
     * <p><b>Why this is the oversell guard, and why it lives HERE.</b> The
     * sellable ceiling is the SUM of category {@code totalSeats}, not
     * {@code event.totalCapacity}: booking-service claims capacity per category
     * ({@code categoryInventoryRepository.tryClaim}, seeded from
     * {@code category.totalSeats}) and its own comment says "the per-category
     * counter — not a seat row — is the oversell guard now". The event's
     * {@code availableTickets} is decremented AFTERWARDS by
     * {@code consumeEventAvailability}, which swallows every failure and blocks
     * nothing — a display mirror, not a gate. So categories summing above
     * {@code totalCapacity} genuinely sell more tickets than the venue holds.
     *
     * <p><b>Why not at approval.</b> {@code Event.rejected} defaults to false
     * and {@code active} to true, so a new event is sellable from creation and
     * never passes through {@code approveEvent} at all; booking-service's
     * {@code EventLookupDTO} carries no state field and never checks one.
     * Guarding approval would leave the common case wide open — the dangerous
     * event is the one nobody ever rejected. Allocation is the moment the
     * over-sale becomes possible, so allocation is where it is refused.
     *
     * <p><b>Under-allocation is allowed on purpose.</b> Only exceeding capacity
     * can oversell; falling short just means not all capacity is on sale, which
     * is the normal state while an organizer adds categories one at a time.
     * Requiring exact equality would refuse every intermediate step of building
     * a seat map.
     *
     * <p><b>Fails CLOSED</b>, for the same reason the delete guard does: an
     * unanswerable capacity is refused (503), never assumed infinite. This adds
     * no new coupling for an organizer — {@code requireEventOwnership} already
     * refuses them when the same lookup comes back empty — but it does newly
     * apply to SUPER_ADMIN, who skips the ownership check. That is deliberate:
     * an admin can oversell a venue exactly as easily as an organizer can.
     */
    private void requireCapacityHeadroom(UUID eventId, int newSeats, String authHeader) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("Category creation refused, event-service client unavailable eventId={}", eventId);
            throw new ServiceUnavailableException(
                    "Cannot verify the event's capacity right now. Please try again shortly.");
        }
        // Deliberately NOT .map(EventLookupDTO::getTotalCapacity).orElseThrow():
        // Optional.map collapses a null mapped value into an empty Optional, so
        // that spelling would fold "event-service did not answer" and "the event
        // reports no capacity" into one branch — the second log line would never
        // fire, and the null check after it would be unreachable. They are the
        // same refusal but not the same fault, and an operator reading the logs
        // needs to tell a lookup failure from a malformed payload.
        EventLookupDTO event = client.fetchEvent(eventId, authHeader)
                .orElseThrow(() -> {
                    log.warn("Category creation refused, event lookup empty eventId={}", eventId);
                    return new ServiceUnavailableException(
                            "Cannot verify the event's capacity right now. Please try again shortly.");
                });
        Integer capacity = event.getTotalCapacity();
        if (capacity == null) {
            // A present event that reports no capacity is not "unlimited" — it is
            // a payload we cannot reason about, so it is refused like an absent one.
            log.warn("Category creation refused, event reports no totalCapacity eventId={}", eventId);
            throw new ServiceUnavailableException(
                    "Cannot verify the event's capacity right now. Please try again shortly.");
        }
        // Sum in long: each category is capped at MAX_TOTAL_SEATS_PER_CATEGORY,
        // but enough categories could still overflow int in aggregate.
        long allocated = categoryRepository.findByEventIdAndDeletedFalse(eventId).stream()
                .mapToLong(c -> c.getTotalSeats() == null ? 0L : c.getTotalSeats())
                .sum();
        long proposed = allocated + newSeats;
        if (proposed > capacity) {
            log.warn("Category creation refused, would exceed event capacity eventId={} "
                            + "allocated={} requested={} capacity={}",
                    eventId, allocated, newSeats, capacity);
            throw new ConflictException(
                    "This event's seat categories already account for " + allocated
                            + " of " + capacity + " seats. Adding " + newSeats
                            + " more would exceed the event's capacity by " + (proposed - capacity)
                            + ". Raise the event's capacity or reduce this category.");
        }
    }

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
                    dto.setImageUrl(normalizeImageUrl(section.getImageUrl()));
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
