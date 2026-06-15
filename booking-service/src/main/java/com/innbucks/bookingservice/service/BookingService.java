package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.LoyaltyServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.*;
import com.innbucks.bookingservice.entity.*;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.DependencyUnavailableException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.exception.TierRequirementException;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.loyalty.LoyaltyEarnRetryService;
import com.innbucks.bookingservice.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final CategoryInventoryRepository categoryInventoryRepository;
    private final SeatServiceClient seatServiceClient;
    private final ApplicationEventPublisher eventPublisher;
    private final QrCodeGenerator qrCodeGenerator;
    // ObjectProvider so unit tests that build the service with `new` and pass
    // null for the loyalty/event clients still work — the loyalty integration
    // is only exercised by tests that opt in.
    private final ObjectProvider<LoyaltyServiceClient> loyaltyClientProvider;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final LoyaltyEarnRetryService loyaltyEarnRetryService;
    private final TransactionTemplate txTemplate;

    // CSPRNG for ticket-number generation. A ticket number IS the QR-code
    // payload a gate scanner validates, so it's an entry credential, not a
    // cosmetic id — it must be unpredictable. java.util.Random is a 48-bit
    // LCG whose entire future stream a holder of one ticket can reconstruct,
    // letting them forge a neighbour's ticket. SecureRandom closes that.
    // Static + thread-safe: one shared instance avoids per-call reseed cost.
    private static final SecureRandom TICKET_RNG = new SecureRandom();

    // Fuzz tolerance for split-payment math: pointsToUse / redeemRate +
    // cashAmount must equal totalAmount within $0.01.
    private static final BigDecimal SPLIT_TOLERANCE = new BigDecimal("0.01");

    private static final int TIER_2_MAX_SEATS = 2;
    private static final int TIER_3_MAX_SEATS = 6;
    private static final int TIER_4_MAX_SEATS = 10;

    // How long a PENDING booking holds its seats before the expiration
    // scheduler auto-cancels it. Java-side default of 5 means unit tests that
    // construct BookingService with `new` (no Spring) get a sensible value;
    // Spring overrides via @Value at runtime.
    @org.springframework.beans.factory.annotation.Value("${app.booking.hold-ttl-minutes:5}")
    private long holdTtlMinutes = 5;

    // Shared secret passed to event-service on the internal
    // PATCH /events/{id}/availability/consume call. Same value as
    // INTERNAL_API_TOKEN on the event-service end of the wire.
    @org.springframework.beans.factory.annotation.Value("${innbucks.internal-api-token:}")
    private String eventInternalToken;

    public BookingService(BookingRepository bookingRepository,
                          BookingItemRepository bookingItemRepository,
                          CategoryInventoryRepository categoryInventoryRepository,
                          SeatServiceClient seatServiceClient,
                          ApplicationEventPublisher eventPublisher,
                          QrCodeGenerator qrCodeGenerator,
                          ObjectProvider<LoyaltyServiceClient> loyaltyClientProvider,
                          ObjectProvider<EventServiceClient> eventClientProvider,
                          LoyaltyEarnRetryService loyaltyEarnRetryService,
                          PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.categoryInventoryRepository = categoryInventoryRepository;
        this.seatServiceClient = seatServiceClient;
        this.eventPublisher = eventPublisher;
        this.qrCodeGenerator = qrCodeGenerator;
        this.loyaltyClientProvider = loyaltyClientProvider;
        this.eventClientProvider = eventClientProvider;
        this.loyaltyEarnRetryService = loyaltyEarnRetryService;
        // Programmatic transaction wrapping ONLY the booking writes (see
        // createBooking -> persistBooking). Mirrors the TransactionTemplate
        // pattern in loyalty-service ShopService / user-service AuditService.
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public BookingResponseDTO createBooking(
            String userEmail,
            int tier,
            String phoneNumber,
            CreateBookingRequestDTO request
    ) {
        log.info("Creating booking userEmail={} tier={} eventId={} seats={}",
                userEmail, tier, request.getEventId(), request.getSeats().size());

        int maxSeats = maxSeatsForTier(tier);
        if (request.getSeats().size() > maxSeats) {
            log.warn("Booking rejected, exceeds tier seat limit userEmail={} tier={} requested={} max={}",
                    userEmail, tier, request.getSeats().size(), maxSeats);
            throw new TierRequirementException(
                    minTierForSeatCount(request.getSeats().size()),
                    tier,
                    "Tier " + tier + " customers may book at most " + maxSeats + " seats per booking");
        }

        // === Resolution phase — NO transaction, NO DB connection held. ===
        // Every seat-service / event-service round trip below runs BEFORE the
        // write transaction opens, so a pooled DB connection is never held
        // across a blocking upstream call (that capped throughput at
        // poolSize/callDuration and drained Hikari under load).
        //
        // GA inventory model: tickets in a category are fungible — we work in
        // quantities per category, not specific seats. Capacity is enforced by
        // an atomic per-category counter in persistBooking, NOT by picking a
        // synthetic seat row (which caused the hot-event 409 storm where
        // concurrent bookers randomly collided on the same seat UUIDs even
        // when capacity remained).

        // Collapse the per-ticket request into a quantity per category.
        Map<UUID, Integer> qtyByCategory = new LinkedHashMap<>();
        for (CreateBookingRequestDTO.SeatItemRequest item : request.getSeats()) {
            qtyByCategory.merge(item.getCategoryId(), 1, Integer::sum);
        }

        // Resolve each category's capacity + price + owning event from
        // seat-service, and validate the category belongs to the event.
        Map<UUID, CategoryLookupDTO> categoryById = new LinkedHashMap<>();
        for (UUID categoryId : qtyByCategory.keySet()) {
            CategoryLookupDTO category = lookupCategory(categoryId);
            if (!request.getEventId().equals(category.getEventId())) {
                log.warn("Booking rejected, category does not belong to event userEmail={} categoryId={} requestEventId={} actualEventId={}",
                        userEmail, categoryId, request.getEventId(), category.getEventId());
                throw new BadRequestException(
                        "This ticket category does not belong to event '"
                                + request.getEventId() + "'. Please pick a category for the right event.");
            }
            categoryById.put(categoryId, category);
        }

        // Total = sum over categories of price × quantity.
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> e : qtyByCategory.entrySet()) {
            BigDecimal price = categoryById.get(e.getKey()).getPrice();
            total = total.add(price.multiply(BigDecimal.valueOf(e.getValue())));
        }

        String confirmationNumber = generateConfirmationNumber();

        // Tickets are held for holdTtlMinutes; if no /confirm (= payment) lands
        // by then, the expiration scheduler flips the booking to CANCELLED and
        // releases the held tickets back to the category counter.
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(holdTtlMinutes);

        // Resolve the owning tenant once so loyalty earn/redeem can be
        // attributed at confirm without another event-service round trip.
        // Best-effort; skipped for guest bookings (no loyalty account).
        String tenantId = userEmail == null
                ? null
                : lookupTenantId(request.getEventId());

        Booking booking = Booking.builder()
                .userEmail(userEmail)
                .phoneNumber(phoneNumber)
                .eventId(request.getEventId())
                .tenantId(tenantId)
                .confirmationNumber(confirmationNumber)
                .status(Booking.BookingStatus.PENDING)
                .totalAmount(total)
                .expiresAt(expiresAt)
                .build();

        // === Write phase — the ONLY place a DB connection is held. ===
        return txTemplate.execute(status -> persistBooking(booking, qtyByCategory, categoryById, userEmail));
    }

    /**
     * Persists a fully-resolved booking and its per-seat items in one short
     * transaction. Extracted from {@link #createBooking} so the DB connection
     * is acquired only for these writes — never across the upstream seat- and
     * event-service calls made during the resolution phase. Runs inside
     * createBooking's {@code txTemplate.execute} callback.
     */
    private BookingResponseDTO persistBooking(Booking booking,
                                              Map<UUID, Integer> qtyByCategory,
                                              Map<UUID, CategoryLookupDTO> categoryById,
                                              String userEmail) {
        // Claim capacity atomically, per category, in THIS transaction. The
        // per-category counter — not a seat row — is the oversell guard now.
        // Seed the counter once per category (remaining = total_seats −
        // existing active items) then decrement by the requested quantity under
        // the row lock. A failed claim (sold out) throws, rolling back the
        // whole transaction and releasing any categories already claimed in
        // this same request — no manual compensation needed within a request.
        for (Map.Entry<UUID, Integer> e : qtyByCategory.entrySet()) {
            UUID categoryId = e.getKey();
            int qty = e.getValue();
            CategoryLookupDTO category = categoryById.get(categoryId);

            // Seed is exact, not racy: seeding precedes the booking_items
            // insert below, so the active-item count is a stable committed
            // baseline. INSERT ... ON CONFLICT DO NOTHING means only the first
            // booking for a category sets the value; everyone after uses the
            // existing row + the atomic decrement.
            long activeItems = bookingItemRepository.countActiveByCategoryId(
                    categoryId, Booking.BookingStatus.CANCELLED);
            int totalSeats = category.getTotalSeats() == null ? 0 : category.getTotalSeats();
            int seed = Math.max(0, totalSeats - (int) activeItems);
            categoryInventoryRepository.seedIfAbsent(categoryId, seed);

            int claimed = categoryInventoryRepository.tryClaim(categoryId, qty);
            if (claimed == 0) {
                log.warn("Booking rejected, category sold out userEmail={} categoryId={} qty={}",
                        userEmail, categoryId, qty);
                throw new BookingConflictException(
                        "Not enough tickets left in \"" + category.getName() + "\". Please choose fewer tickets or a different category.");
            }
        }

        bookingRepository.save(booking);

        // One booking_item per ticket — each carries its own unique ticket
        // number (the QR credential a gate scanner validates). GA tickets have
        // no assigned seat, so seatId is a synthetic per-ticket UUID (keeps the
        // column non-null and the legacy uq_active_booking_item_per_seat index
        // trivially satisfied) and row/seat labels are GA placeholders.
        List<BookingItem> items = new ArrayList<>();
        int ticketIndex = 1;
        for (Map.Entry<UUID, Integer> e : qtyByCategory.entrySet()) {
            UUID categoryId = e.getKey();
            CategoryLookupDTO category = categoryById.get(categoryId);
            for (int i = 0; i < e.getValue(); i++) {
                items.add(BookingItem.builder()
                        .booking(booking)
                        .seatId(UUID.randomUUID())
                        .categoryId(categoryId)
                        .rowLabel("GA")
                        .seatNumber(ticketIndex++)
                        .categoryName(category.getName())
                        .priceAtBooking(category.getPrice())
                        .ticketNumber(generateTicketNumber())
                        .build());
            }
        }
        bookingItemRepository.saveAllAndFlush(items);
        booking.setItems(items);
        bookingRepository.save(booking);

        List<UUID> ticketIds = items.stream().map(BookingItem::getSeatId).toList();
        eventPublisher.publishEvent(BookingDomainEvent.BookingCreated.of(booking, ticketIds));

        log.info("Booking created bookingId={} confirmation={} userEmail={} eventId={} total={} items={}",
                booking.getId(), booking.getConfirmationNumber(), userEmail, booking.getEventId(),
                booking.getTotalAmount(), items.size());
        return toDTO(booking);
    }

    /** Fetch a category's capacity + price + owning event from seat-service. */
    private CategoryLookupDTO lookupCategory(UUID categoryId) {
        ApiResult<CategoryLookupDTO> envelope = seatServiceClient.getCategory(categoryId);
        if (envelope == null || envelope.getData() == null) {
            log.warn("Category lookup returned no data categoryId={}", categoryId);
            throw new NotFoundException("This ticket category is no longer available.");
        }
        return envelope.getData();
    }

    /**
     * Return a cancelled/expired/reversed booking's tickets to their category
     * counters. Idempotent on the normal paths because each booking transitions
     * to CANCELLED exactly once (the status guards in cancel/expire/reverse),
     * so release runs once per booking.
     */
    private void releaseInventory(Booking booking) {
        if (booking.getItems() == null || booking.getItems().isEmpty()) {
            return;
        }
        Map<UUID, Long> qtyByCategory = booking.getItems().stream()
                .collect(Collectors.groupingBy(BookingItem::getCategoryId, Collectors.counting()));
        qtyByCategory.forEach((categoryId, qty) ->
                categoryInventoryRepository.release(categoryId, qty.intValue()));
    }

    // Returns one DTO per booked seat in the category, regardless of booking
    // status. Consumers (e.g. seat-service analytics) decide whether to
    // exclude CANCELLED themselves.
    //
    // Ownership: callers other than SUPER_ADMIN must own the event the category
    // belongs to. We resolve ownership from the first booking's eventId — every
    // BookingItem in the same category points at the same event, so this is
    // safe. If there are no bookings yet we still verify the category's event
    // by going to event-service via the category's eventId on the booking item
    // (best-effort). When no bookings exist we just return an empty list (no
    // data to leak).
    public List<CategoryBookingDTO> getBookingsByCategory(UUID categoryId,
                                                          String requesterEmail,
                                                          boolean isAdmin) {
        log.debug("Fetching bookings by category categoryId={} requesterEmail={} isAdmin={}",
                categoryId, requesterEmail, isAdmin);
        var items = bookingItemRepository.findByCategoryIdWithBooking(categoryId);
        if (items.isEmpty()) {
            return List.of();
        }
        if (!isAdmin) {
            UUID eventId = items.get(0).getBooking().getEventId();
            requireEventOwnership(eventId, requesterEmail);
        }
        return items.stream()
                .map(this::toCategoryBookingDTO)
                .collect(Collectors.toList());
    }

    // Returns the count of active (PENDING + CONFIRMED, i.e. anything not
    // CANCELLED) booking items per eventId. event-service uses this to compute
    // availableTickets on read so its responses tally with seat-service's
    // category counter (which drops the instant a booking goes PENDING — the
    // seat is LOCKED at that point). Counting only CONFIRMED here let the
    // event card stay at "12 left" while the per-category card already said
    // "10 left", confusing the customer.
    public List<EventActiveCountDTO> getActiveItemCountsByEvents(Collection<UUID> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        return bookingItemRepository
                .countActiveItemsByEventIds(eventIds, Booking.BookingStatus.CANCELLED)
                .stream()
                .map(p -> EventActiveCountDTO.builder()
                        .eventId(p.getEventId())
                        .count(p.getCount() == null ? 0L : p.getCount())
                        .build())
                .collect(Collectors.toList());
    }

    // Same shape as getBookingsByCategory but scoped to a whole event. Lets
    // seat-service build event-level analytics in one round trip instead of
    // calling per-category.
    //
    // Ownership: callers other than SUPER_ADMIN must own the event.
    public List<CategoryBookingDTO> getBookingsByEvent(UUID eventId,
                                                       String requesterEmail,
                                                       boolean isAdmin) {
        log.debug("Fetching bookings by event eventId={} requesterEmail={} isAdmin={}",
                eventId, requesterEmail, isAdmin);
        if (!isAdmin) {
            requireEventOwnership(eventId, requesterEmail);
        }
        return bookingItemRepository.findByEventIdWithBooking(eventId)
                .stream()
                .map(this::toCategoryBookingDTO)
                .collect(Collectors.toList());
    }

    /**
     * Looks up the event in event-service and throws AccessDeniedException if
     * its tenantId doesn't match the requester. SUPER_ADMIN callers bypass
     * this check at the controller layer and never reach here.
     */
    private void requireEventOwnership(UUID eventId, String requesterEmail) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing analytics for eventId={} to non-admin",
                    eventId);
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot verify event ownership");
        }
        try {
            var lookup = client.getEvent(eventId);
            if (lookup == null || lookup.getData() == null) {
                throw new org.springframework.security.access.AccessDeniedException("Event not found");
            }
            String ownerTenantId = lookup.getData().getTenantId();
            if (ownerTenantId == null || !ownerTenantId.equals(requesterEmail)) {
                log.warn("Event ownership check failed eventId={} requesterEmail={} ownerTenantId={}",
                        eventId, requesterEmail, ownerTenantId);
                throw new org.springframework.security.access.AccessDeniedException(
                        "You do not own this event");
            }
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Event ownership lookup failed eventId={} cause={}", eventId, e.toString());
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot verify event ownership");
        }
    }

    private CategoryBookingDTO toCategoryBookingDTO(BookingItem item) {
        Booking b = item.getBooking();
        return CategoryBookingDTO.builder()
                .bookingId(b.getId())
                .userEmail(b.getUserEmail())
                .eventId(b.getEventId())
                .status(b.getStatus())
                .confirmationNumber(b.getConfirmationNumber())
                .seatId(item.getSeatId())
                .categoryId(item.getCategoryId())
                .categoryName(item.getCategoryName())
                .rowLabel(item.getRowLabel())
                .seatNumber(item.getSeatNumber())
                .ticketNumber(item.getTicketNumber())
                .priceAtBooking(item.getPriceAtBooking())
                .bookedAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .expiresAt(b.getExpiresAt())
                .build();
    }

    public List<BookingResponseDTO> getMyBookings(String userEmail) {
        log.debug("Fetching bookings userEmail={}", userEmail);
        return bookingRepository.findByUserEmail(userEmail)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Phone-number lookup (most-recent first). One phone may have many
    // bookings; the caller decides how to render the list.
    public List<BookingResponseDTO> getByPhoneNumber(String phoneNumber) {
        log.debug("Fetching bookings phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        return bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Returns only CONFIRMED bookings for a phone number — excludes PENDING and
    // CANCELLED so customers see only paid, valid tickets.
    public List<BookingResponseDTO> getActiveByPhoneNumber(String phoneNumber) {
        log.debug("Fetching confirmed bookings phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        return bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber)
                .stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.CONFIRMED)
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail) {
        return getBookingById(bookingId, userEmail, false);
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail, boolean isAdmin) {
        log.debug("Fetching booking bookingId={} userEmail={} isAdmin={}", bookingId, userEmail, isAdmin);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Booking lookup failed, not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        if (!isAdmin && !booking.getUserEmail().equals(userEmail)) {
            log.warn("Booking access denied bookingId={} requesterEmail={} ownerEmail={}",
                    bookingId, userEmail, booking.getUserEmail());
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        return toDTO(booking);
    }

    public BookingResponseDTO getByConfirmationNumber(String confirmationNumber) {
        log.debug("Fetching booking by confirmation confirmation={}", confirmationNumber);
        return toDTO(bookingRepository
                .findByConfirmationNumber(confirmationNumber)
                .orElseThrow(() -> {
                    log.warn("Booking lookup by confirmation failed, not found confirmation={}",
                            confirmationNumber);
                    return new RuntimeException("Booking not found");
                }));
    }

    @Transactional
    public BookingResponseDTO cancelBooking(UUID bookingId, String userEmail) {
        return cancelBooking(bookingId, userEmail, false);
    }

    @Transactional
    public BookingResponseDTO cancelBooking(UUID bookingId, String userEmail, boolean isAdmin) {
        log.info("Cancelling booking bookingId={} userEmail={} isAdmin={}", bookingId, userEmail, isAdmin);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Cancel failed, booking not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        if (!isAdmin && !booking.getUserEmail().equals(userEmail)) {
            log.warn("Cancel rejected, access denied bookingId={} requesterEmail={} ownerEmail={}",
                    bookingId, userEmail, booking.getUserEmail());
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            log.warn("Cancel rejected, booking already confirmed bookingId={}", bookingId);
            throw new BookingConflictException(
                    "We can't cancel this confirmed booking — payment has already been processed. Please contact support if you need a refund."
            );
        }

        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            log.warn("Cancel rejected, booking already cancelled bookingId={}", bookingId);
            throw new BookingConflictException("This booking is already cancelled.");
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setExpiresAt(null); // hold no longer applicable
        bookingRepository.save(booking);

        // Return the held tickets to their category counters. Runs once: a
        // booking can only transition PENDING -> CANCELLED here (the guards
        // above reject an already-CANCELLED booking), so no double-release.
        releaseInventory(booking);

        eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));

        log.info("Booking cancelled bookingId={} userEmail={}", bookingId, userEmail);
        return toDTO(booking);
    }

    /**
     * SUPER_ADMIN reversal of a CONFIRMED booking — admin refund, no-show, or
     * (once veengu real payments land) a money-transfer failure that arrives
     * after the booking was already CONFIRMED. Restores the event's stored
     * {@code availableTickets} via event-service so the seats return to the
     * available pool, then flips the booking to CANCELLED.
     *
     * <p>The release call is idempotent per booking via
     * {@link Booking#isAvailabilityReleased()}: a successful release flips the
     * flag, so a retried reversal short-circuits the release and never
     * double-credits. If the release call fails (event-service unreachable
     * mid-call), the booking is NOT marked CANCELLED — the admin retries the
     * same call until release succeeds, then the state transition lands.
     */
    @Transactional
    public BookingResponseDTO reverseConfirmedBooking(UUID bookingId, String adminEmail) {
        log.info("Reversing confirmed booking bookingId={} adminEmail={}", bookingId, adminEmail);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Reverse failed, booking not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            // Caller can only reverse a CONFIRMED booking. PENDING uses /cancel;
            // already-CANCELLED is a no-op the caller shouldn't be hitting.
            log.warn("Reverse rejected, booking not confirmed bookingId={} status={}",
                    bookingId, booking.getStatus());
            throw new BookingConflictException(
                    "This booking can't be reversed at its current stage. Only CONFIRMED bookings can be reversed.");
        }

        if (!booking.isAvailabilityReleased()) {
            // Event-service release FIRST (remote, may throw → admin retries
            // with nothing yet mutated). Then the local category-counter
            // release. Both guarded by the one-shot availabilityReleased flag.
            // Known rare edge: if the local release threw after the remote one
            // succeeded, a retry would hit the event-service over-cap clamp;
            // that's a manual-fix case for this rare admin path (a follow-up
            // can add a per-booking release ledger to make both idempotent).
            releaseEventAvailability(booking); // throws on failure → admin retries → no partial state
            releaseInventory(booking);
            booking.setAvailabilityReleased(true);
        } else {
            log.info("Skipping release call, already released bookingId={}", bookingId);
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));

        log.info("Booking reversed bookingId={} adminEmail={}", bookingId, adminEmail);
        return toDTO(booking);
    }

    private void releaseEventAvailability(Booking booking) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            throw new DependencyUnavailableException("event-service client unavailable; cannot release availability");
        }
        int count = booking.getItems() == null ? 0 : booking.getItems().size();
        if (count <= 0) {
            // Nothing was consumed (no items) — nothing to release. Idempotent no-op.
            return;
        }
        ApiResult<AvailabilityResponseDTO> envelope =
                client.releaseAvailability(booking.getEventId(), count, eventInternalToken);
        AvailabilityResponseDTO data = envelope == null ? null : envelope.getData();
        if (data == null) {
            // Fallback returned null payload (circuit open / event-service down).
            // Do NOT flip the idempotency flag — retry must call event-service again.
            throw new DependencyUnavailableException("event-service rejected/skipped release"
                    + (envelope == null ? "" : ": " + envelope.getMessage()));
        }
        log.info("Released event availability eventId={} released={} remaining={}",
                booking.getEventId(), count, data.getAvailableTickets());
    }

    // Backward-compatible overload: confirm with no points/cash split. Used
    // by callers (tests, payment-service) that don't yet know about loyalty.
    /**
     * Extend a PENDING booking's seat hold to AT LEAST {@code holdUntil} —
     * called S2S by payment-service the moment it is about to mint an InnBucks
     * payment code, so the hold provably outlives the code the customer is
     * shown (hold 5 min vs code 10 min was the paid-but-no-ticket gap: pay
     * after the hold lapsed -> confirm refused -> money stuck in the ops
     * queue). Never SHORTENS a hold (max of existing and requested).
     *
     * <p>Refuses (409) when the booking is not PENDING or the hold has already
     * lapsed — the caller then refuses the payment BEFORE any money moves,
     * which beats resurrecting a hold the sweeper may be cancelling (the
     * optimistic @Version on Booking arbitrates any direct race).
     */
    @Transactional
    public BookingResponseDTO extendHold(UUID bookingId, LocalDateTime holdUntil) {
        Objects.requireNonNull(holdUntil, "holdUntil");
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            throw new BookingConflictException(
                    "This booking's seat hold can no longer be extended — it's not in a PENDING state.");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now)) {
            throw new BookingConflictException(
                    "Your seat reservation has expired. Please start a new booking and complete payment within "
                            + holdTtlMinutes + " minutes.");
        }
        if (booking.getExpiresAt() == null || booking.getExpiresAt().isBefore(holdUntil)) {
            log.info("Extending seat hold bookingId={} from={} to={}",
                    bookingId, booking.getExpiresAt(), holdUntil);
            booking.setExpiresAt(holdUntil);
            booking = bookingRepository.save(booking);
        }
        return toDTO(booking);
    }

    public BookingResponseDTO confirmBooking(UUID bookingId) {
        return confirmBooking(bookingId, null);
    }

    @Transactional
    public BookingResponseDTO confirmBooking(UUID bookingId, ConfirmBookingRequestDTO confirmRequest) {
        log.info("Confirming booking bookingId={} pointsToUse={} cashAmount={}",
                bookingId,
                confirmRequest == null ? null : confirmRequest.getPointsToUse(),
                confirmRequest == null ? null : confirmRequest.getCashAmount());
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Confirm failed, booking not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        // CONFIRMED: idempotent replay. payment-service's Idempotency-Key cache
        // holds the original response for 24h, but a retry that lands beyond
        // that window — or arrives from a different replica that lost its
        // in-memory cache — would otherwise see status != PENDING and 409 even
        // though the booking IS confirmed. False-rejection surfaces to the
        // customer as "your booking failed" right after a successful confirm.
        // Returning the existing DTO is the correct retry semantics.
        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            log.info("Confirm idempotent replay (already CONFIRMED) bookingId={}", bookingId);
            return toDTO(booking);
        }
        // CANCELLED is still a real error — a CANCELLED booking can't be confirmed.
        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            log.warn("Confirm rejected, booking not pending bookingId={} status={}",
                    bookingId, booking.getStatus());
            throw new BookingConflictException("Only pending bookings can be confirmed — this one isn't in that state.");
        }

        // Reject confirms that arrive after the seat hold has lapsed — the
        // expiration scheduler may not have run yet, but the lock is gone.
        if (booking.getExpiresAt() != null
                && booking.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            log.warn("Confirm rejected, hold expired bookingId={} expiredAt={}",
                    bookingId, booking.getExpiresAt());
            throw new BookingConflictException(
                    "Your seat reservation has expired. Please start a new booking and complete payment within "
                            + holdTtlMinutes + " minutes.");
        }

        // Default to pure-cash if the caller didn't pass a payload. This
        // preserves the legacy contract where PATCH /confirm has no body.
        BigDecimal pointsToUse = confirmRequest == null || confirmRequest.getPointsToUse() == null
                ? BigDecimal.ZERO : confirmRequest.getPointsToUse();
        BigDecimal cashAmount = confirmRequest == null || confirmRequest.getCashAmount() == null
                ? booking.getTotalAmount() : confirmRequest.getCashAmount();

        applyLoyalty(booking, pointsToUse, cashAmount);

        booking.setPointsUsed(pointsToUse.signum() > 0 ? pointsToUse : null);
        booking.setCashAmount(cashAmount);
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        booking.setExpiresAt(null); // paid — hold no longer applicable
        bookingRepository.save(booking);

        // Decrement the event's stored availableTickets in event-service so its
        // DB column actually drops as tickets are bought. Best-effort: if the
        // gateway is degraded the booking still confirms and the event-service
        // read-time enrichment keeps the response in sync until next call.
        consumeEventAvailability(booking);

        eventPublisher.publishEvent(BookingDomainEvent.BookingConfirmed.of(booking));

        log.info("Booking confirmed bookingId={} userEmail={} pointsUsed={} cashAmount={}",
                bookingId, booking.getUserEmail(), booking.getPointsUsed(), booking.getCashAmount());
        return toDTO(booking);
    }

    // Validates the points/cash split against the tenant's loyalty rule and
    // (if everything checks out) calls redeem then earn on loyalty-service.
    // No-op when:
    //   - the loyalty client isn't wired (unit tests),
    //   - the booking has no tenantId (event-service was down at create),
    //   - both pointsToUse and cashAmount cover the full total via cash only
    //     AND the rule isn't reachable.
    private void applyLoyalty(Booking booking, BigDecimal pointsToUse, BigDecimal cashAmount) {
        LoyaltyServiceClient loyalty = loyaltyClientProvider == null ? null : loyaltyClientProvider.getIfAvailable();
        if (loyalty == null) {
            log.debug("Loyalty client unavailable; skipping loyalty for bookingId={}", booking.getId());
            return;
        }
        if (booking.getTenantId() == null) {
            if (pointsToUse.signum() > 0) {
                throw new BadRequestException("Loyalty points can't be used on this booking.");
            }
            log.debug("No tenantId on booking; skipping loyalty earn for bookingId={}", booking.getId());
            return;
        }

        LoyaltyRuleResponse rule = fetchRule(loyalty, booking.getTenantId());
        if (rule == null) {
            if (pointsToUse.signum() > 0) {
                throw new DependencyUnavailableException("Loyalty rule unavailable; cannot redeem points right now");
            }
            log.debug("Loyalty rule unavailable; skipping earn for bookingId={}", booking.getId());
            return;
        }

        // Cross-check the math: pointsToUse / redeemRate + cashAmount ≈ totalAmount
        BigDecimal pointsAsCash = pointsToUse.signum() == 0
                ? BigDecimal.ZERO
                : pointsToUse.divide(rule.getRedeemRate(), 4, RoundingMode.HALF_UP);
        BigDecimal reconstructedTotal = pointsAsCash.add(cashAmount);
        BigDecimal diff = reconstructedTotal.subtract(booking.getTotalAmount()).abs();
        if (diff.compareTo(SPLIT_TOLERANCE) > 0) {
            throw new BadRequestException(String.format(
                    "Your payment doesn't add up to the booking total (points worth %s + cash %s = %s, expected %s). Please adjust and try again.",
                    pointsAsCash, cashAmount, reconstructedTotal, booking.getTotalAmount()));
        }

        String reference = booking.getId().toString();
        if (pointsToUse.signum() > 0) {
            loyalty.redeem(LoyaltyRedeemRequest.builder()
                    .customerId(booking.getUserEmail())
                    .tenantId(booking.getTenantId())
                    .points(pointsToUse)
                    .reference(reference)
                    .build());
        }
        // Earn ONLY on the cash portion, per the program rule. Pure-points
        // confirmations don't accumulate.
        if (cashAmount.signum() > 0) {
            try {
                loyalty.earn(LoyaltyEarnRequest.builder()
                        .customerId(booking.getUserEmail())
                        .tenantId(booking.getTenantId())
                        .cashAmount(cashAmount)
                        .reference(reference)
                        .build());
            } catch (Exception ex) {
                // Earning is best-effort — we never fail a paid booking
                // because we couldn't credit points. But losing the side-
                // effect silently meant the customer paid and never got
                // their points (only a log line, which nobody monitored).
                //
                // Now we persist a loyalty_earn_retry row inside the
                // confirm transaction so a rolled-back confirm doesn't
                // leave a stray retry. LoyaltyEarnRetryJob drains it on
                // the next tick with exponential backoff; max-attempts
                // exhaustion flips the row to `giving_up` and trips a
                // Prometheus alert via the gauge it publishes.
                loyaltyEarnRetryService.enqueue(
                        booking.getId(),
                        booking.getUserEmail(),
                        booking.getTenantId(),
                        cashAmount,
                        reference,
                        ex);
            }
        }
    }

    private LoyaltyRuleResponse fetchRule(LoyaltyServiceClient loyalty, String tenantId) {
        try {
            ApiResult<LoyaltyRuleResponse> envelope = loyalty.getRule(tenantId);
            return envelope == null ? null : envelope.getData();
        } catch (Exception ex) {
            log.warn("Failed to fetch loyalty rule tenantId={} reason={}", tenantId, ex.getMessage());
            return null;
        }
    }

    private void consumeEventAvailability(Booking booking) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("event client unavailable; skipping availability decrement bookingId={}",
                    booking.getId());
            return;
        }
        int count = booking.getItems() == null ? 0 : booking.getItems().size();
        if (count <= 0) {
            return;
        }
        try {
            ApiResult<AvailabilityResponseDTO> envelope =
                    client.consumeAvailability(booking.getEventId(), count, eventInternalToken);
            AvailabilityResponseDTO data = envelope == null ? null : envelope.getData();
            if (data != null) {
                log.info("Decremented event availability eventId={} consumed={} remaining={}",
                        booking.getEventId(), count, data.getAvailableTickets());
            } else {
                log.warn("Availability decrement returned no data eventId={} consumed={}",
                        booking.getEventId(), count);
            }
        } catch (Exception ex) {
            // Don't block confirmation on event-service trouble. The read-time
            // enrichment in event-service still subtracts confirmed booking
            // items so the API response stays correct.
            log.warn("Failed to decrement event availability eventId={} count={} reason={}",
                    booking.getEventId(), count, ex.getMessage());
        }
    }

    private String lookupTenantId(UUID eventId) {
        EventServiceClient event = eventClientProvider == null ? null : eventClientProvider.getIfAvailable();
        if (event == null) {
            return null;
        }
        try {
            ApiResult<EventLookupDTO> envelope = event.getEvent(eventId);
            EventLookupDTO data = envelope == null ? null : envelope.getData();
            return data == null ? null : data.getTenantId();
        } catch (Exception ex) {
            log.warn("Failed to fetch event for tenant lookup eventId={} reason={}", eventId, ex.getMessage());
            return null;
        }
    }

    // ==================== HELPERS ====================

    private int maxSeatsForTier(int tier) {
        return switch (tier) {
            case 2 -> TIER_2_MAX_SEATS;
            case 3 -> TIER_3_MAX_SEATS;
            case 4 -> TIER_4_MAX_SEATS;
            default -> 0;
        };
    }

    // Smallest tier whose per-booking cap can accommodate the requested seat count.
    private int minTierForSeatCount(int seatCount) {
        if (seatCount <= TIER_2_MAX_SEATS) return 2;
        if (seatCount <= TIER_3_MAX_SEATS) return 3;
        return 4;
    }

    private String generateConfirmationNumber() {
        String date = LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 6)
                .toUpperCase();
        return "INN-" + date + "-" + random;
    }

    /**
     * Generates a unique ticket number per seat
     * Format: 20260419-48291X
     * — today's date
     * — 5 random digits
     * — 1 random uppercase letter
     *
     * <p>Randomness comes from {@link #TICKET_RNG} (SecureRandom), NOT
     * {@code java.util.Random}: the value is embedded in the seat's QR code
     * and validated at the gate, so a predictable stream would let a holder
     * of one ticket forge an adjacent one. Format is unchanged — same wire
     * shape the FE already renders.
     */
    private String generateTicketNumber() {
        String date = LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            digits.append(TICKET_RNG.nextInt(10));
        }

        char letter = (char) ('A' + TICKET_RNG.nextInt(26));

        return date + "-" + digits + letter;
    }

    private BookingResponseDTO toDTO(Booking booking) {
        List<BookingItemDTO> itemDTOs = booking.getItems() == null
                ? List.of()
                : booking.getItems().stream()
                  .map(i -> BookingItemDTO.builder()
                            .seatId(i.getSeatId())
                            .categoryId(i.getCategoryId())
                            .categoryName(i.getCategoryName())
                            .rowLabel(i.getRowLabel())
                            .seatNumber(i.getSeatNumber())
                            .priceAtBooking(i.getPriceAtBooking())
                            .ticketNumber(i.getTicketNumber())
                            .qrCode(qrCodeGenerator.toDataUri(i.getTicketNumber()))
                            .build())
                  .collect(Collectors.toList());

        return BookingResponseDTO.builder()
                .id(booking.getId())
                .userEmail(booking.getUserEmail())
                .phoneNumber(booking.getPhoneNumber())
                .eventId(booking.getEventId())
                .tenantId(booking.getTenantId())
                .confirmationNumber(booking.getConfirmationNumber())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .pointsUsed(booking.getPointsUsed())
                .cashAmount(booking.getCashAmount())
                .items(itemDTOs)
                .createdAt(booking.getCreatedAt())
                .updatedAt(booking.getUpdatedAt())
                .expiresAt(booking.getExpiresAt())
                .build();
    }
}
