package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.config.MarketTimeZone;
import com.innbucks.seatservice.dto.*;
import com.innbucks.seatservice.entity.*;
import com.innbucks.seatservice.exception.BadRequestException;
import com.innbucks.seatservice.exception.ConflictException;
import com.innbucks.seatservice.exception.NotFoundException;
import com.innbucks.seatservice.exception.ServiceUnavailableException;
import com.innbucks.seatservice.repository.*;
import com.innbucks.seatservice.util.HtmlSanitizer;
import com.innbucks.seatservice.cache.ReadCacheConfig;
import com.innbucks.seatservice.cache.ReadThroughCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SeatCategoryService {

    // Aggregate cap on top of the DTO's per-section @Max + sections @Size.
    // A request that passes bean validation can still ask for 100 sections ×
    // 100,000 seats = 10 million. This cap rejects that before we materialise
    // the seat rows. Half a million is more than any real venue's capacity.
    public static final long MAX_TOTAL_SEATS_PER_CATEGORY = 500_000L;

    private final SeatCategoryRepository categoryRepository;
    /** Null only in the plain-{@code new} unit tests that predate price tiers: no tiers then. */
    private final SeatCategoryPriceTierRepository priceTierRepository;
    private final SeatRepository seatRepository;
    private final BookingServiceClient bookingServiceClient;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    /** Static category layout per event; see {@link #getCategoriesByEvent}. */
    private final ReadThroughCache layoutCache;

    @org.springframework.beans.factory.annotation.Autowired
    public SeatCategoryService(SeatCategoryRepository categoryRepository,
                               SeatCategoryPriceTierRepository priceTierRepository,
                               SeatRepository seatRepository,
                               BookingServiceClient bookingServiceClient,
                               ObjectProvider<EventServiceClient> eventClientProvider,
                               @org.springframework.beans.factory.annotation.Qualifier(ReadCacheConfig.READ_CACHE_MANAGER)
                               org.springframework.cache.CacheManager cacheManager) {
        this.categoryRepository = categoryRepository;
        this.priceTierRepository = priceTierRepository;
        this.seatRepository = seatRepository;
        this.bookingServiceClient = bookingServiceClient;
        this.eventClientProvider = eventClientProvider;
        this.layoutCache = ReadThroughCache.of(cacheManager, ReadCacheConfig.CATEGORY_LAYOUT);
    }

    /** No price tiers. */
    public SeatCategoryService(SeatCategoryRepository categoryRepository,
                               SeatRepository seatRepository,
                               BookingServiceClient bookingServiceClient,
                               ObjectProvider<EventServiceClient> eventClientProvider,
                               org.springframework.cache.CacheManager cacheManager) {
        this(categoryRepository, null, seatRepository, bookingServiceClient, eventClientProvider, cacheManager);
    }

    /** No layout cache and no price tiers: every listing reads Postgres. */
    public SeatCategoryService(SeatCategoryRepository categoryRepository,
                               SeatRepository seatRepository,
                               BookingServiceClient bookingServiceClient,
                               ObjectProvider<EventServiceClient> eventClientProvider) {
        this(categoryRepository, null, seatRepository, bookingServiceClient, eventClientProvider, null);
    }

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

    /**
     * Reads an early-bird end the organizer typed in the market's local time.
     * A setter for the same reason as the transaction manager. The application
     * always has the bean (a {@code @Component}); the plain-{@code new} unit
     * tests and the slim web-slice contexts that lack it get the ZW cell's zone.
     */
    private MarketTimeZone marketTimeZone = new MarketTimeZone("ZW");

    @Autowired(required = false)
    void setMarketTimeZone(MarketTimeZone marketTimeZone) {
        this.marketTimeZone = marketTimeZone;
    }

    /**
     * Where each price change is recorded (V10). A setter, not required, like
     * the ones above: the plain-{@code new} tests that predate it record nothing.
     */
    private SeatCategoryPriceChangeRepository priceChangeRepository;

    @Autowired(required = false)
    void setPriceChangeRepository(SeatCategoryPriceChangeRepository priceChangeRepository) {
        this.priceChangeRepository = priceChangeRepository;
    }

    /** Decides whether an early bird is still running. Tests move it. */
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
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
        List<TierInput> tierInputs = validateTiers(request.getPriceTiers(), request.getPrice(), Map.of());

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
        List<SeatCategoryPriceTier> tiers = replaceTiers(category.getId(), tierInputs, List.of());
        recordChange(category, SeatCategoryPriceChange.ChangeType.CREATED, null, category.getPrice(),
                null, PriceHistory.snapshot(tiers));

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
        layoutCache.evict(request.getEventId());

        log.info("Seat category created categoryId={} eventId={} name={} totalSeats={}",
                category.getId(), request.getEventId(), request.getName(), totalSeats);
        // Just created — no bookings yet, so the whole category is available
        // and nothing is sold at any tier. Skip the booking-service round trip.
        return toCreateResponseDTO(category, request.getSections(), category.getAvailableSeats(),
                tiers, Map.of());
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
        //
        //
        // The price tiers go out as terms only, read now, never from a cache:
        // booking-service prices each ticket from them, counting what each tier
        // has sold under its own stock lock.
        return CategoryCapacityDTO.builder()
                .seatCategoryId(category.getId())
                .eventId(category.getEventId())
                .name(category.getName())
                .price(category.getPrice())
                .priceTiers(CategoryPricing.terms(tierTermsOf(tiersOf(categoryId))))
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
        CreateCategoryResponseDTO updated = editCategory(categoryId, callerOrganizerUuid, requesterEmail, isAdmin,
                authHeader, category -> {
            // Defence-in-depth on top of the DTO @DecimalMin (only fires on @Valid
            // controller calls), same as createCategory.
            if (request.getPrice() == null || request.getPrice().signum() <= 0) {
                log.warn("Category update rejected, non-positive price categoryId={} price={}",
                        categoryId, request.getPrice());
                throw new BadRequestException("Price must be greater than 0.");
            }

            // Every tier that can still apply must stay below the price, or the
            // "discount" would cost the same or more. A tier past its end no
            // longer matters, so it never blocks a price change. A sold-out one
            // still counts: an expired hold can reopen it.
            LocalDateTime now = nowUtc();
            for (SeatCategoryPriceTier tier : tiersOf(categoryId)) {
                boolean ended = tier.getEndsAt() != null && !now.isBefore(tier.getEndsAt());
                if (!ended && request.getPrice().compareTo(tier.getPrice()) <= 0) {
                    log.warn("Category update rejected, price not above an open tier categoryId={} price={} "
                            + "tierId={} tierPrice={}", categoryId, request.getPrice(), tier.getId(), tier.getPrice());
                    throw new BadRequestException("The ticket price must be higher than the price tier \""
                            + tier.getName() + "\" (" + money(tier.getPrice()) + "). Lower or remove that tier first.");
                }
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

            BigDecimal oldPrice = category.getPrice();
            category.setName(HtmlSanitizer.stripAll(request.getName()));
            category.setDescription(HtmlSanitizer.stripAll(request.getDescription()));
            category.setPrice(request.getPrice());
            if (oldPrice == null || oldPrice.compareTo(request.getPrice()) != 0) {
                recordChange(category, SeatCategoryPriceChange.ChangeType.PRICE_CHANGED, oldPrice,
                        request.getPrice(), null, null);
            }
        });
        log.info("Seat category updated categoryId={} eventId={}", categoryId, updated.getEventId());
        return updated;
    }

    /**
     * Replace a category's price tiers with {@code request.tiers}, in the order
     * they apply; an empty list removes them all. A tier sent with its
     * {@code id} is updated in place, so the tickets already sold at it still
     * count toward its quantity; one without an id is new; a stored tier left
     * out is deleted. Bookings already made keep their price
     * ({@code priceAtBooking}), so no change here restates a sold ticket. Same
     * owner rule and phases as {@link #updateCategory}.
     */
    public CreateCategoryResponseDTO setPriceTiers(UUID categoryId,
                                                   PriceTiersRequestDTO request,
                                                   UUID callerOrganizerUuid,
                                                   String requesterEmail,
                                                   boolean isAdmin,
                                                   String authHeader) {
        return editCategory(categoryId, callerOrganizerUuid, requesterEmail, isAdmin, authHeader, category -> {
            List<SeatCategoryPriceTier> existing = tiersOf(categoryId);
            Map<UUID, SeatCategoryPriceTier> existingById = new LinkedHashMap<>();
            existing.forEach(tier -> existingById.put(tier.getId(), tier));
            List<TierInput> inputs = validateTiers(request.getTiers(), category.getPrice(), existingById);
            // Snapshot before replaceTiers: it updates the kept tiers in place.
            String before = PriceHistory.snapshot(existing);
            List<SeatCategoryPriceTier> saved = replaceTiers(categoryId, inputs, existing);
            String after = PriceHistory.snapshot(saved);
            if (!before.equals(after)) {
                recordChange(category, SeatCategoryPriceChange.ChangeType.TIERS_CHANGED, null, null, before, after);
            }
            log.info("Price tiers set categoryId={} eventId={} tiers={} replaced={} requesterEmail={} isAdmin={}",
                    categoryId, category.getEventId(), saved.size(), existing.size(), requesterEmail, isAdmin);
        });
    }

    /**
     * The phases every category edit shares, so no remote call runs while a
     * transaction is open (see {@link #updateCategory}): an organizer's
     * ownership check with none open, then one write transaction that re-reads
     * the category (404 if it was deleted meanwhile), applies {@code change},
     * saves and evicts the layout, then the live counts for the response after
     * the commit.
     */
    private CreateCategoryResponseDTO editCategory(UUID categoryId,
                                                   UUID callerOrganizerUuid,
                                                   String requesterEmail,
                                                   boolean isAdmin,
                                                   String authHeader,
                                                   Consumer<SeatCategory> change) {
        if (!isAdmin) {
            SeatCategory found = TransactionPhases.inTransaction(readTx, () -> loadLiveForUpdate(categoryId));
            requireEventOwnership(found.getEventId(), callerOrganizerUuid, requesterEmail, authHeader);
        }

        record Edited(SeatCategory category, List<SectionSeatConfigDTO> sections, List<SeatCategoryPriceTier> tiers) {
        }
        Edited edited = TransactionPhases.inTransaction(writeTx, () -> {
            SeatCategory category = loadLiveForUpdate(categoryId);
            change.accept(category);
            categoryRepository.save(category);
            layoutCache.evict(category.getEventId());

            // Return the same shape getCategoriesByEvent emits — sections rebuilt
            // from the persisted seats (unchanged by this edit).
            return new Edited(category, sectionsForCategory(categoryId), tiersOf(categoryId));
        });

        // Live availability and tier counts for the response, after the commit
        // (each degrades rather than failing if booking-service is down).
        Map<UUID, Long> counts = bookingServiceClient
                .fetchActiveCountsByCategories(List.of(categoryId))
                .orElse(null);
        SeatCategory category = edited.category();
        Map<UUID, CategoryPricing.Sold> sold = soldByTier(categoryId, tierSoldCounts(
                Map.of(categoryId, tierTermsOf(edited.tiers()))));
        return toCreateResponseDTO(category, edited.sections(), liveAvailableSeats(category, counts),
                edited.tiers(), sold);
    }

    /**
     * Record one price change, in the caller's transaction, so a change that
     * rolls back leaves no history and a history row always describes a change
     * that happened. {@code changed_by} is stamped by JPA auditing.
     */
    private void recordChange(SeatCategory category, SeatCategoryPriceChange.ChangeType type,
                              BigDecimal oldPrice, BigDecimal newPrice, String tiersBefore, String tiersAfter) {
        if (priceChangeRepository == null) {
            return;
        }
        priceChangeRepository.save(SeatCategoryPriceChange.builder()
                .categoryId(category.getId())
                .eventId(category.getEventId())
                .changeType(type)
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .tiersBefore(tiersBefore)
                .tiersAfter(tiersAfter)
                .changedAt(nowUtc())
                .build());
    }

    /**
     * A category's price history, newest first (at most 200 changes): when the
     * full price moved, when the tiers were replaced (each side's terms as they
     * were), and who did it. The owning EVENT_ORGANIZER or SUPER_ADMIN; a
     * deleted category's history stays readable. The ownership check asks
     * event-service with no transaction open, like the edits.
     */
    public List<PriceChangeDTO> getPriceHistory(UUID categoryId,
                                                UUID callerOrganizerUuid,
                                                String requesterEmail,
                                                boolean isAdmin,
                                                String authHeader) {
        SeatCategory category = TransactionPhases.inTransaction(readTx, () -> categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Seat category not found")));
        if (!isAdmin) {
            requireEventOwnership(category.getEventId(), callerOrganizerUuid, requesterEmail, authHeader);
        }
        if (priceChangeRepository == null) {
            return List.of();
        }
        return TransactionPhases.inTransaction(readTx,
                        () -> priceChangeRepository.findTop200ByCategoryIdOrderByChangedAtDesc(categoryId))
                .stream()
                .map(PriceHistory::toDto)
                .toList();
    }

    /** One validated tier before it is saved; {@code id} names the stored tier it updates, or is null. */
    private record TierInput(UUID id, String name, BigDecimal price, LocalDateTime startsAtUtc,
                             LocalDateTime endsAtUtc, Integer quantity, Integer maxPerCustomer,
                             boolean refundsReopen) {
    }

    /**
     * Check a requested tier list against the category's full price. Re-checks
     * what bean validation checks too, for callers that skip it. Nothing is
     * written: every refusal comes before the first save.
     *
     * @param existingById the category's stored tiers, by id (empty on create)
     */
    private List<TierInput> validateTiers(List<PriceTierRequestDTO> requested, BigDecimal fullPrice,
                                          Map<UUID, SeatCategoryPriceTier> existingById) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > PriceTiersRequestDTO.MAX_TIERS) {
            throw new BadRequestException("At most " + PriceTiersRequestDTO.MAX_TIERS
                    + " price tiers per category.");
        }
        LocalDateTime now = nowUtc();
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        List<TierInput> inputs = new ArrayList<>(requested.size());
        for (PriceTierRequestDTO tier : requested) {
            if (tier == null) {
                throw new BadRequestException("Every price tier needs a name.");
            }
            String name = tier.getName() == null ? "" : HtmlSanitizer.stripAll(tier.getName()).trim();
            if (name.isEmpty()) {
                throw new BadRequestException("Every price tier needs a name.");
            }
            if (name.length() > 60) {
                throw new BadRequestException("Tier name must be at most 60 characters.");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Two price tiers are named \"" + name
                        + "\". Give each tier its own name.");
            }
            SeatCategoryPriceTier stored = null;
            if (tier.getId() != null) {
                if (!ids.add(tier.getId())) {
                    throw new BadRequestException("Price tier " + tier.getId() + " is listed twice.");
                }
                stored = existingById.get(tier.getId());
                if (stored == null) {
                    log.warn("Price tiers rejected, foreign tier id tierId={}", tier.getId());
                    throw new BadRequestException("Price tier " + tier.getId() + " does not belong to this category.");
                }
            }
            BigDecimal price = tier.getPrice();
            if (price == null || price.signum() <= 0) {
                throw new BadRequestException("The price of \"" + name + "\" must be greater than 0.");
            }
            if (price.stripTrailingZeros().scale() > 2) {
                throw new BadRequestException("The price of \"" + name + "\" can have at most 2 decimal places.");
            }
            if (fullPrice != null && price.compareTo(fullPrice) >= 0) {
                log.warn("Price tiers rejected, tier not below the ticket price tierPrice={} price={}", price, fullPrice);
                throw new BadRequestException("The price of \"" + name + "\" must be lower than the ticket price ("
                        + money(fullPrice) + ").");
            }
            Integer quantity = tier.getQuantity();
            if (quantity != null && quantity <= 0) {
                throw new BadRequestException("The number of tickets for \"" + name + "\" must be at least 1.");
            }
            Integer maxPerCustomer = tier.getMaxPerCustomer();
            if (maxPerCustomer != null && maxPerCustomer <= 0) {
                throw new BadRequestException("The limit per customer for \"" + name + "\" must be at least 1.");
            }
            LocalDateTime startsAtUtc = null;
            if (tier.getStartsAt() != null && !tier.getStartsAt().isBlank()) {
                try {
                    startsAtUtc = marketTimeZone.parseToUtc(tier.getStartsAt());
                } catch (DateTimeParseException e) {
                    throw new BadRequestException("The start of \"" + name
                            + "\" must be a date and time like 2026-09-05T18:00:00.");
                }
            }
            LocalDateTime endsAtUtc = null;
            if (tier.getEndsAt() != null && !tier.getEndsAt().isBlank()) {
                try {
                    endsAtUtc = marketTimeZone.parseToUtc(tier.getEndsAt());
                } catch (DateTimeParseException e) {
                    throw new BadRequestException("The end of \"" + name
                            + "\" must be a date and time like 2026-09-08T23:59:59.");
                }
                // A kept tier whose end has passed and is sent back unchanged is
                // fine: the organizer is editing the tiers after it.
                boolean unchanged = stored != null && endsAtUtc.equals(stored.getEndsAt());
                if (!unchanged && !endsAtUtc.isAfter(now)) {
                    log.warn("Price tiers rejected, end not in the future endsAtUtc={}", endsAtUtc);
                    throw new BadRequestException("The end of \"" + name + "\" must be in the future.");
                }
            }
            if (endsAtUtc == null && quantity == null) {
                throw new BadRequestException("\"" + name + "\" needs an end date, a number of tickets, or both.");
            }
            if (startsAtUtc != null && endsAtUtc != null && !startsAtUtc.isBefore(endsAtUtc)) {
                throw new BadRequestException("The start of \"" + name + "\" must be before its end.");
            }
            inputs.add(new TierInput(tier.getId(), name, price, startsAtUtc, endsAtUtc, quantity, maxPerCustomer,
                    tier.getRefundsReopen() == null || tier.getRefundsReopen()));
        }
        return inputs;
    }

    /**
     * Write {@code inputs} as the category's tiers, in order: kept tiers are
     * updated in place, new ones inserted, and stored tiers left out deleted.
     * The order constraint is deferred (V9), so a reorder never trips it mid-flush.
     */
    private List<SeatCategoryPriceTier> replaceTiers(UUID categoryId, List<TierInput> inputs,
                                                     List<SeatCategoryPriceTier> existing) {
        if (priceTierRepository == null) {
            if (!inputs.isEmpty()) {
                throw new IllegalStateException("Price tiers need the price tier repository");
            }
            return List.of();
        }
        Map<UUID, SeatCategoryPriceTier> existingById = new LinkedHashMap<>();
        existing.forEach(tier -> existingById.put(tier.getId(), tier));
        Set<UUID> keptIds = new HashSet<>();
        List<SeatCategoryPriceTier> rows = new ArrayList<>(inputs.size());
        for (int position = 0; position < inputs.size(); position++) {
            TierInput input = inputs.get(position);
            SeatCategoryPriceTier row = input.id() == null
                    ? SeatCategoryPriceTier.builder().categoryId(categoryId).build()
                    : existingById.get(input.id());
            if (input.id() != null) {
                keptIds.add(input.id());
            }
            row.setPosition(position);
            row.setName(input.name());
            row.setPrice(input.price());
            row.setStartsAt(input.startsAtUtc());
            row.setEndsAt(input.endsAtUtc());
            row.setQuantity(input.quantity());
            row.setMaxPerCustomer(input.maxPerCustomer());
            row.setRefundsReopen(input.refundsReopen());
            rows.add(row);
        }
        List<SeatCategoryPriceTier> removed = existing.stream()
                .filter(tier -> !keptIds.contains(tier.getId()))
                .toList();
        if (!removed.isEmpty()) {
            priceTierRepository.deleteAll(removed);
        }
        return rows.isEmpty() ? List.of() : priceTierRepository.saveAll(rows);
    }

    /** A category's stored tiers in the order they apply; empty without the repository. */
    private List<SeatCategoryPriceTier> tiersOf(UUID categoryId) {
        return priceTierRepository == null ? List.of()
                : priceTierRepository.findByCategoryIdOrderByPositionAsc(categoryId);
    }

    private static List<CategoryPricing.Tier> tierTermsOf(List<SeatCategoryPriceTier> tiers) {
        return tiers.stream().map(CategoryPricing.Tier::of).toList();
    }

    /**
     * Tickets sold per tier, for display, asked of booking-service only for
     * categories that have tiers. Null when it could not be asked (tiers then
     * show without counts); an absent category or tier has sold nothing.
     */
    private Map<UUID, Map<UUID, CategoryPricing.Sold>> tierSoldCounts(
            Map<UUID, List<CategoryPricing.Tier>> tiersByCategory) {
        List<UUID> withTiers = tiersByCategory.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (withTiers.isEmpty()) {
            return Map.of();
        }
        return bookingServiceClient.fetchTierSoldCounts(withTiers).orElse(null);
    }

    private static Map<UUID, CategoryPricing.Sold> soldByTier(UUID categoryId,
                                                              Map<UUID, Map<UUID, CategoryPricing.Sold>> soldCounts) {
        return soldCounts == null ? null : soldCounts.getOrDefault(categoryId, Map.of());
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
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

    /**
     * The event's live categories for the public listing.
     *
     * <p><b>Two halves, cached differently.</b> The LAYOUT — which categories,
     * their name, description, price and price-tier terms, total seats and
     * section breakdown — is
     * cached per event ({@code seats.cache.category-layout.ttl}, 60s) and
     * evicted by this pod's create / update / delete of a category in that
     * event. Seats are generated once, at category creation, so section counts
     * only move with those writes. AVAILABILITY is never cached: the
     * booking-service active counts are asked on every call, and when
     * booking-service cannot answer, the stored mirror is read from Postgres
     * now (or taken from the rows this very call loaded) rather than from the
     * cache. Another replica can serve the old layout for up to the TTL.
     * {@code currentPrice} and each tier's status are not part of the layout:
     * they are worked out per request from the cached terms and live tier
     * counts, so a tier closes on time everywhere.
     *
     * <p>None of the write guards read through this: the capacity-headroom
     * check queries the repository itself and the delete guard asks
     * booking-service directly, both fail closed.
     */
    public List<CreateCategoryResponseDTO> getCategoriesByEvent(UUID eventId) {
        log.debug("Fetching seat categories eventId={}", eventId);
        Map<UUID, Integer> mirrorLoadedNow = new java.util.HashMap<>();
        EventLayout layout = layoutCache.get(eventId, () -> {
            List<SeatCategory> categories = categoryRepository.findByEventIdAndDeletedFalse(eventId);
            categories.forEach(c -> mirrorLoadedNow.put(c.getId(), c.getAvailableSeats()));
            List<UUID> ids = categories.stream().map(SeatCategory::getId).collect(Collectors.toList());
            return EventLayout.of(categories, sectionsByCategory(ids), tiersByCategory(ids));
        });
        List<UUID> categoryIds = layout.categories().stream()
                .map(CategoryLayout::id)
                .collect(Collectors.toList());

        // One booking-service round trip covers every category in the event;
        // null counts (booking-service down) → each category falls back to
        // its stored mirror inside liveAvailableSeats.
        Map<UUID, Long> counts = bookingServiceClient
                .fetchActiveCountsByCategories(categoryIds)
                .orElse(null);
        Map<UUID, Integer> mirror = counts != null || categoryIds.isEmpty()
                ? Map.of()
                : (mirrorLoadedNow.isEmpty() ? storedMirror(categoryIds) : mirrorLoadedNow);

        // The price in force is worked out for THIS request, not when the
        // layout was cached, so a tier closes on time on every replica. Tier
        // counts are asked live, like availability, and only for categories
        // that have tiers.
        Map<UUID, List<CategoryPricing.Tier>> tiersByCategory = new LinkedHashMap<>();
        layout.categories().forEach(c -> tiersByCategory.put(c.id(), c.tiers()));
        Map<UUID, Map<UUID, CategoryPricing.Sold>> soldCounts = tierSoldCounts(tiersByCategory);
        LocalDateTime now = nowUtc();
        return layout.categories().stream()
                .map(category -> category.toDto(liveAvailableSeats(
                        category.id(), category.totalSeats(), mirror.get(category.id()), counts),
                        soldByTier(category.id(), soldCounts), now))
                .collect(Collectors.toList());
    }

    /** The stored availableSeats mirror, read now — the degraded path only. */
    private Map<UUID, Integer> storedMirror(List<UUID> categoryIds) {
        Map<UUID, Integer> mirror = new java.util.HashMap<>();
        for (SeatCategory category : categoryRepository.findAllById(categoryIds)) {
            mirror.put(category.getId(), category.getAvailableSeats());
        }
        return mirror;
    }

    /** Several categories' tiers in one query, each in the order it applies. */
    private Map<UUID, List<SeatCategoryPriceTier>> tiersByCategory(List<UUID> categoryIds) {
        if (priceTierRepository == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<SeatCategoryPriceTier>> byCategory = new LinkedHashMap<>();
        for (SeatCategoryPriceTier tier
                : priceTierRepository.findByCategoryIdInOrderByCategoryIdAscPositionAsc(categoryIds)) {
            byCategory.computeIfAbsent(tier.getCategoryId(), k -> new ArrayList<>()).add(tier);
        }
        return byCategory;
    }

    /** One event's cached layout: immutable, never availability, never which price applies. */
    record EventLayout(List<CategoryLayout> categories) {
        static EventLayout of(List<SeatCategory> categories, Map<UUID, List<SectionSeatConfigDTO>> sections,
                              Map<UUID, List<SeatCategoryPriceTier>> tiers) {
            return new EventLayout(categories.stream()
                    .map(c -> new CategoryLayout(c.getId(), c.getEventId(), c.getName(), c.getDescription(),
                            c.getPrice(), tierTermsOf(tiers.getOrDefault(c.getId(), List.of())), c.getTotalSeats(),
                            sections.getOrDefault(c.getId(), List.of()).stream()
                                    .map(sec -> new SectionLayout(sec.getSection(), sec.getSeatCount(),
                                            sec.getImageUrl()))
                                    .toList()))
                    .toList());
        }
    }

    record CategoryLayout(UUID id, UUID eventId, String name, String description,
                          BigDecimal price, List<CategoryPricing.Tier> tiers,
                          Integer totalSeats, List<SectionLayout> sections) {

        /** A fresh response DTO, in exactly {@code toCreateResponseDTO}'s shape. */
        CreateCategoryResponseDTO toDto(Integer availableSeats, Map<UUID, CategoryPricing.Sold> soldByTier,
                                        LocalDateTime nowUtc) {
            List<SectionSeatConfigDTO> sectionCopies = sections.stream()
                    .map(section -> {
                        SectionSeatConfigDTO dto = new SectionSeatConfigDTO();
                        dto.setSection(section.section());
                        dto.setSeatCount(section.seatCount());
                        dto.setImageUrl(normalizeImageUrl(section.imageUrl()));
                        return dto;
                    })
                    .collect(Collectors.toList());
            CategoryPricing.Priced priced = CategoryPricing.price(price, tiers, soldByTier, nowUtc);
            return CreateCategoryResponseDTO.builder()
                    .seatCategoryId(id)
                    .eventId(eventId)
                    .name(name)
                    .description(description)
                    .price(price)
                    .currentPrice(priced.currentPrice())
                    .currentTierName(priced.currentTierName())
                    .priceTiers(priced.tiers())
                    .availableSeats(availableSeats)
                    .sections(sectionCopies)
                    .build();
        }
    }

    record SectionLayout(String section, int seatCount, String imageUrl) {
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
        layoutCache.evict(category.getEventId());
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
            Integer availableSeats,
            List<SeatCategoryPriceTier> tiers,
            Map<UUID, CategoryPricing.Sold> soldByTier
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

        CategoryPricing.Priced priced = CategoryPricing.price(category.getPrice(), tierTermsOf(tiers),
                soldByTier, nowUtc());
        return CreateCategoryResponseDTO.builder()
                .seatCategoryId(category.getId())
                .eventId(category.getEventId())
                .name(category.getName())
                .description(category.getDescription())
                .price(category.getPrice())
                .currentPrice(priced.currentPrice())
                .currentTierName(priced.currentTierName())
                .priceTiers(priced.tiers())
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
        return liveAvailableSeats(category.getId(), category.getTotalSeats(), category.getAvailableSeats(), counts);
    }

    private static int liveAvailableSeats(UUID categoryId, Integer totalSeats, Integer storedAvailable,
                                          Map<UUID, Long> counts) {
        int total = totalSeats == null ? 0 : totalSeats;
        if (counts == null) {
            return storedAvailable == null ? total : storedAvailable;
        }
        long active = counts.getOrDefault(categoryId, 0L);
        return (int) Math.max(0L, total - active);
    }
}
