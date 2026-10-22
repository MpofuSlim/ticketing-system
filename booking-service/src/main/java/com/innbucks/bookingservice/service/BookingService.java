package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.cache.EventLookupCache;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.*;
import com.innbucks.bookingservice.entity.*;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.DependencyUnavailableException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final CategoryInventoryRepository categoryInventoryRepository;
    // Built here from the inventory repository, not injected, so the many
    // hand-built test instances keep their constructor (same reason as the
    // event-lookup cache's setter below).
    private final StockLedger stockLedger;
    private final SeatServiceClient seatServiceClient;
    private final ApplicationEventPublisher eventPublisher;
    private final QrCodeGenerator qrCodeGenerator;
    // ObjectProvider so unit tests that build the service with `new` and pass
    // null for the event client still work.
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private volatile EventLookupCache eventLookupCache;
    private volatile BookingMetrics metrics;
    // Ticket and confirmation numbers. A working default, replaced by the
    // Spring bean through its setter, so hand-built test instances keep their
    // constructor and still mint real (SecureRandom) references.
    private volatile BookingReferences references = new BookingReferences();
    private final TransactionTemplate txTemplate;
    // Read-only twin for the list reads (see loadWithItems): the bookings and
    // their LAZY items are read inside it; mapping (QR rendering) and any
    // remote call run after it has closed.
    private final TransactionTemplate readTx;

    // Fuzz tolerance for split-payment math: pointsToUse / redeemRate +
    // cashAmount must equal totalAmount within $0.01.
    private static final BigDecimal SPLIT_TOLERANCE = new BigDecimal("0.01");

    // A generated reference that clashed with one already stored (23505 on
    // one of these two UNIQUE constraints, V1) rolled the whole attempt back,
    // stock claim included, so the claim runs again with FRESH references.
    // Bounded: at 50 bits a ticket clash is not expected to happen at all,
    // and a confirmation clash four times in a row for one booking is
    // (bookings that day / 16.7M)^4. Any other 23505 is not a clash of ours.
    static final String TICKET_NUMBER_CONSTRAINT = "uk_booking_items_ticket_number";
    static final String CONFIRMATION_NUMBER_CONSTRAINT = "uk_bookings_confirmation_number";
    static final int MAX_COLLISION_RETRIES = 3;
    private static final String UNIQUE_VIOLATION = "23505";
    // Postgres' own wording; Hibernate's PostgreSQLDialect parses the same template.
    private static final String UNIQUE_CONSTRAINT_TEMPLATE = "violates unique constraint \"";

    // A deadlock (40P01) or serialization failure (40001) in the claim
    // transaction rolled the whole attempt back, so running it again from the
    // same resolved inputs is safe. Postgres picks one of the waiters as the
    // victim; the survivor has committed by the time the victim retries.
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "40001");

    /** {@code data.errorCode} of the 503 a claim answers when its retry fails the same way. */
    public static final String TRY_AGAIN = "TRY_AGAIN";

    /** {@code data.errorCode} of the 409 a cancel answers while a payment may still land. */
    public static final String PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";

    /** The cancel refusal's message; also the Swagger example's. */
    public static final String PAYMENT_IN_PROGRESS_MESSAGE = "A payment for this booking is still open. "
            + "If you don't complete it, the booking is released automatically.";

    /**
     * Confirm's refusal for a booking it cannot confirm: cancelled for good
     * (an admin reversal, or before V25), or cancelled and not resurrectable
     * right now (sold out, category deleted). payment-service reads any 409
     * as "keep the payment and retry", so every one of them is this body.
     */
    static final String NOT_CONFIRMABLE_MESSAGE =
            "Only pending bookings can be confirmed — this one isn't in that state.";

    // Flat per-booking seat ceiling, applied identically to every customer.
    // This replaced a per-tier ladder (tier 2 -> 10, 3 -> 15, 4 -> 20, anything
    // else -> 0, i.e. a hard block); the tier ladder is gone, so everyone now
    // gets what the top rung used to get. The cap itself is NOT a tier check —
    // it's the inventory-abuse guard that stops one request reserving a whole
    // venue, and CreateBookingRequestDTO.seats carries no @Size, so deleting it
    // outright would leave booking unbounded.
    @org.springframework.beans.factory.annotation.Value("${app.booking.max-seats-per-booking:20}")
    private int maxSeatsPerBooking = 20;

    // How long a PENDING booking holds its seats before the expiration
    // scheduler auto-cancels it. Java-side default of 5 means unit tests that
    // construct BookingService with `new` (no Spring) get a sensible value;
    // Spring overrides via @Value at runtime.
    @org.springframework.beans.factory.annotation.Value("${app.booking.hold-ttl-minutes:5}")
    private long holdTtlMinutes = 5;

    // The absolute hold cap (D26): extend-hold refuses a hold that would run
    // past created_at + this, so no payment instrument is minted for it.
    // Without it a client re-minting codes could hold tickets for ever
    // without paying. Refused, never clamped: a hold clamped short under a
    // live instrument is a payment that lands with no ticket. Set through
    // setMaxTotalHold below.
    private Duration maxTotalHold = Duration.ofMinutes(60);

    // Per-call sanity bound on extend-hold: a hold more than this far from
    // NOW is refused. It sits above the longest rail (card, 33 min), so with
    // the configured rails it never fires; when it does, a rail's TTL was
    // raised without raising this (alert HoldExtensionRefused). Set through
    // setMaxHoldExtension below.
    private Duration maxHoldExtension = Duration.ofMinutes(45);

    // Shared secret passed to event-service on the internal
    // PATCH /events/{id}/availability/release call and the internal event
    // lookup. Same value as INTERNAL_API_TOKEN on the event-service end of
    // the wire.
    @org.springframework.beans.factory.annotation.Value("${innbucks.internal-api-token:}")
    private String eventInternalToken;

    public BookingService(BookingRepository bookingRepository,
                          BookingItemRepository bookingItemRepository,
                          CategoryInventoryRepository categoryInventoryRepository,
                          SeatServiceClient seatServiceClient,
                          ApplicationEventPublisher eventPublisher,
                          QrCodeGenerator qrCodeGenerator,
                          ObjectProvider<EventServiceClient> eventClientProvider,
                          PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.categoryInventoryRepository = categoryInventoryRepository;
        this.stockLedger = new StockLedger(categoryInventoryRepository);
        this.seatServiceClient = seatServiceClient;
        this.eventPublisher = eventPublisher;
        this.qrCodeGenerator = qrCodeGenerator;
        this.eventClientProvider = eventClientProvider;
        // Programmatic transaction wrapping ONLY the booking writes (see
        // createBooking -> persistBooking). Mirrors the TransactionTemplate
        // pattern in loyalty-service ShopService / user-service AuditService.
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public BookingResponseDTO createBooking(
            String userEmail,
            String phoneNumber,
            CreateBookingRequestDTO request
    ) {
        log.info("Creating booking userEmail={} eventId={} seats={}",
                userEmail, request.getEventId(), request.getSeats().size());

        if (request.getSeats().size() > maxSeatsPerBooking) {
            log.warn("Booking rejected, exceeds per-booking seat limit userEmail={} requested={} max={}",
                    userEmail, request.getSeats().size(), maxSeatsPerBooking);
            throw new BadRequestException(
                    "You may book at most " + maxSeatsPerBooking + " seats per booking.");
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

        // Defence in depth — the controller resolves this (body, else JWT
        // name) and 400s when neither exists. A caller reaching the service
        // directly without one is a wiring bug, not a booking.
        String customerName = request.getCustomerName() == null ? null : request.getCustomerName().trim();
        if (customerName == null || customerName.isEmpty()) {
            throw new BadRequestException("Please provide your full name.");
        }

        // Collapse the per-ticket request into a quantity per category (the
        // capacity claim is per category), but ALSO keep the per-ticket order
        // — each request entry may name its own attendee, and the ticket
        // issued for entry i must carry entry i's attendee, not be reshuffled
        // by category grouping.
        //
        // The quantities are keyed in the CANONICAL lock order (StockLedger),
        // not the order the request named them: persistBooking claims in this
        // map's order, and two bookings naming [A,B] and [B,A] used to lock the
        // two counter rows in opposite orders and deadlock.
        Map<UUID, Integer> qtyByCategory = StockLedger.byCategory();
        List<TicketSpec> ticketSpecs = new ArrayList<>(request.getSeats().size());
        for (CreateBookingRequestDTO.SeatItemRequest item : request.getSeats()) {
            qtyByCategory.merge(item.getCategoryId(), 1, Integer::sum);
            ticketSpecs.add(TicketSpec.of(item));
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

        // The total is NOT worked out here: a ticket's price can depend on how
        // many tickets a price tier has already sold, which is only exact once
        // the category's stock row is locked. persistBooking prices every
        // ticket and sums the total inside the claim.

        // Capture the owning organizer's stable cross-service uuid so the
        // ticket scan path can authorize the redeemer (events.tenant_user_uuid
        // must equal the scanner's JWT organizerUuid) and loyalty earn/redeem
        // can attribute to the right merchant. Best-effort: if event-service is
        // unreachable lookupEvent returns null and the booking is created
        // without it — a scan of such a ticket later refuses with
        // WRONG_ORGANIZER. Runs for guests too (previously guarded on
        // userEmail != null, which left every guest ticket unscannable at the
        // gate and invisible in organizer revenue reports).
        EventLookupDTO eventLookup = lookupEvent(request.getEventId());
        UUID tenantUserUuid = eventLookup == null ? null : eventLookup.getTenantUserUuid();

        ClaimInputs inputs = new ClaimInputs(userEmail, customerName, phoneNumber, request.getEventId(),
                tenantUserUuid, qtyByCategory, categoryById, ticketSpecs);

        // === Write phase — the ONLY place a DB connection is held. ===
        return claimWithRetries(inputs);
    }

    /**
     * Everything the claim transaction needs, resolved before it opens. A
     * retry starts again from here, never from the entities the failed attempt
     * built: those were saved into a rolled-back transaction, and saving the
     * same {@link Booking} a second time fails as an optimistic-lock error.
     */
    private record ClaimInputs(String userEmail, String customerName, String phoneNumber, UUID eventId,
                               UUID tenantUserUuid, Map<UUID, Integer> qtyByCategory,
                               Map<UUID, CategoryLookupDTO> categoryById, List<TicketSpec> ticketSpecs) {
    }

    /**
     * Runs the claim transaction, retrying two kinds of failure that rolled
     * the whole attempt back (stock claim included), each on its own budget:
     *
     * <ul>
     *   <li><b>A deadlock or serialization failure</b> — run ONCE more. The
     *       canonical lock order should make it impossible; the retry is the
     *       backstop for a lock taken somewhere this service does not control.
     *       A second one is a 503 the caller can retry, not the 500 a deadlock
     *       used to be.</li>
     *   <li><b>A generated reference that clashed</b> with one already stored
     *       (a ticket or confirmation number, see {@link #collidedReference}) —
     *       run again up to {@value #MAX_COLLISION_RETRIES} times, counted on
     *       {@code booking.claim.reference_collision}. Exhausted, it is the same
     *       503 TRY_AGAIN. A clash used to be a 500.</li>
     * </ul>
     *
     * Neither budget spends the other. Every attempt builds a fresh booking
     * with a fresh confirmation number, hold and ticket numbers. The logs name
     * the kind of reference and the attempt, never a value: a ticket number is
     * an entry credential.
     */
    private BookingResponseDTO claimWithRetries(ClaimInputs inputs) {
        boolean deadlockRetried = false;
        int collisionRetries = 0;
        while (true) {
            try {
                BookingResponseDTO created = claimOnce(inputs);
                if (deadlockRetried) {
                    recordClaimRetry(BookingMetrics.ClaimRetryOutcome.SUCCEEDED);
                }
                return created;
            } catch (RuntimeException failure) {
                BookingMetrics.ReferenceKind collided = collidedReference(failure);
                if (collided != null) {
                    recordReferenceCollision(collided);
                    if (collisionRetries < MAX_COLLISION_RETRIES) {
                        collisionRetries++;
                        log.warn("Claim hit a {} number collision, retrying with fresh references "
                                        + "(collision retry {}/{}) eventId={}",
                                collided.tag(), collisionRetries, MAX_COLLISION_RETRIES, inputs.eventId());
                        continue;
                    }
                    if (deadlockRetried) {
                        // Not FAILED: that means a second deadlock. This 503 is the
                        // collision budget's, and reference_collision counts it.
                        recordClaimRetry(BookingMetrics.ClaimRetryOutcome.REFUSED);
                    }
                    log.warn("Claim hit a {} number collision again after {} collision retries, answering 503 "
                                    + "eventId={}",
                            collided.tag(), MAX_COLLISION_RETRIES, inputs.eventId());
                    throw tryAgain();
                }
                String sqlState = retryableSqlState(failure);
                if (sqlState == null) {
                    if (deadlockRetried) {
                        recordClaimRetry(BookingMetrics.ClaimRetryOutcome.REFUSED);
                    }
                    throw failure;
                }
                if (!deadlockRetried) {
                    deadlockRetried = true;
                    log.warn("Claim transaction failed with SQLState {}, retrying once eventId={} categories={}",
                            sqlState, inputs.eventId(), inputs.qtyByCategory().keySet());
                    continue;
                }
                recordClaimRetry(BookingMetrics.ClaimRetryOutcome.FAILED);
                log.warn("Claim retry failed with SQLState {} too, answering 503 eventId={} categories={}",
                        sqlState, inputs.eventId(), inputs.qtyByCategory().keySet());
                throw tryAgain();
            }
        }
    }

    private static ApiProblemException tryAgain() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, TRY_AGAIN,
                "We couldn't reserve your tickets just now. Please try again.");
    }

    private BookingResponseDTO claimOnce(ClaimInputs inputs) {
        // Tickets are held for holdTtlMinutes; if no /confirm (= payment) lands
        // by then, the expiration scheduler flips the booking to CANCELLED and
        // releases the held tickets back to the category counter.
        Booking booking = Booking.builder()
                .userEmail(inputs.userEmail())
                .customerName(inputs.customerName())
                .phoneNumber(inputs.phoneNumber())
                .eventId(inputs.eventId())
                .tenantUserUuid(inputs.tenantUserUuid())
                .confirmationNumber(references.nextConfirmationNumber())
                .status(Booking.BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(holdTtlMinutes))
                .build();
        return txTemplate.execute(status -> persistBooking(booking, inputs.qtyByCategory(),
                inputs.categoryById(), inputs.ticketSpecs(), inputs.userEmail()));
    }

    /** The SQLState that makes {@code ex} worth one retry (40P01 / 40001), else null. */
    static String retryableSqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /**
     * Which generated reference {@code ex} reports a clash of — a unique
     * violation (23505) on {@value #TICKET_NUMBER_CONSTRAINT} or
     * {@value #CONFIRMATION_NUMBER_CONSTRAINT} — else null, including for any
     * other 23505. The constraint is read from the driver's own exception
     * first (its message starts with Postgres' text; a batch wrapper's message
     * starts with the statement, whose bound values a caller chose), then from
     * Hibernate's parse of it. Walks the cause chain and each
     * {@link SQLException#getNextException()}.
     */
    static BookingMetrics.ReferenceKind collidedReference(Throwable ex) {
        String constraint = violatedUniqueConstraint(ex);
        if (TICKET_NUMBER_CONSTRAINT.equalsIgnoreCase(constraint)) {
            return BookingMetrics.ReferenceKind.TICKET;
        }
        if (CONFIRMATION_NUMBER_CONSTRAINT.equalsIgnoreCase(constraint)) {
            return BookingMetrics.ReferenceKind.CONFIRMATION;
        }
        return null;
    }

    private static String violatedUniqueConstraint(Throwable ex) {
        String fromHibernate = null;
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (ex != null) {
            pending.add(ex);
        }
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable t = pending.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t instanceof SQLException sql) {
                if (!(sql instanceof BatchUpdateException) && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                    String named = constraintNamedIn(sql.getMessage());
                    if (named != null) {
                        return named;
                    }
                }
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            if (fromHibernate == null && t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && UNIQUE_VIOLATION.equals(cve.getSQLState())) {
                fromHibernate = cve.getConstraintName();
            }
            if (t.getCause() != null) {
                pending.add(t.getCause());
            }
        }
        return fromHibernate;
    }

    private static String constraintNamedIn(String message) {
        if (message == null) {
            return null;
        }
        int start = message.indexOf(UNIQUE_CONSTRAINT_TEMPLATE);
        if (start < 0) {
            return null;
        }
        start += UNIQUE_CONSTRAINT_TEMPLATE.length();
        int end = message.indexOf('"', start);
        return end < 0 ? null : message.substring(start, end);
    }

    private void recordClaimRetry(BookingMetrics.ClaimRetryOutcome outcome) {
        BookingMetrics m = metrics;
        if (m != null) {
            m.claimRetry(outcome);
        }
    }

    private void recordReferenceCollision(BookingMetrics.ReferenceKind kind) {
        BookingMetrics m = metrics;
        if (m != null) {
            m.referenceCollision(kind);
        }
    }

    /**
     * One requested ticket, in request order: its category plus the optional
     * attendee the purchaser named for it. Resolved from the request BEFORE
     * the write transaction so persistBooking is pure writes.
     */
    private record TicketSpec(UUID categoryId, String attendeeName, String attendeeEmail, String attendeePhone) {
        static TicketSpec of(CreateBookingRequestDTO.SeatItemRequest item) {
            CreateBookingRequestDTO.AttendeeRequest a = item.getAttendee();
            if (a == null) {
                return new TicketSpec(item.getCategoryId(), null, null, null);
            }
            return new TicketSpec(item.getCategoryId(),
                    blankToNull(a.getFullName()), blankToNull(a.getEmail()), blankToNull(a.getPhoneNumber()));
        }

        private static String blankToNull(String v) {
            if (v == null) return null;
            String t = v.trim();
            return t.isEmpty() ? null : t;
        }
    }

    /**
     * Persists a fully-resolved booking and its per-seat items in one short
     * transaction. Extracted from {@link #createBooking} so the DB connection
     * is acquired only for these writes — never across the upstream seat- and
     * event-service calls made during the resolution phase. Runs inside
     * {@link #claimOnce}'s {@code txTemplate.execute} callback, once per
     * attempt. {@code qtyByCategory} is in the canonical lock order, so the
     * counter rows are claimed in it.
     */
    private BookingResponseDTO persistBooking(Booking booking,
                                              Map<UUID, Integer> qtyByCategory,
                                              Map<UUID, CategoryLookupDTO> categoryById,
                                              List<TicketSpec> ticketSpecs,
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

        // Price every ticket, now that each category's stock row is locked by
        // this transaction: a price tier's quantity and per-customer cap are
        // counted from the items other bookings committed, and none can
        // commit, cancel, expire or reverse a ticket of these categories until
        // this transaction ends. The counts are asked only for tiers that are
        // open and limited.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<UUID, Iterator<PriceTierAllocator.TicketPrice>> pricesByCategory = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> e : qtyByCategory.entrySet()) {
            CategoryLookupDTO category = categoryById.get(e.getKey());
            PriceTierAllocator.Counts toCount = PriceTierAllocator.toCount(category, now);
            Map<UUID, Long> counted = new HashMap<>();
            Map<UUID, Long> customer = new HashMap<>();
            if (!toCount.none()) {
                countTiers(e.getKey(), toCount, booking, counted, customer);
            }
            List<PriceTierAllocator.TicketPrice> prices =
                    PriceTierAllocator.allocate(category, e.getValue(), counted, customer, now);
            for (PriceTierAllocator.TicketPrice price : prices) {
                total = total.add(price.price());
            }
            pricesByCategory.put(e.getKey(), prices.iterator());
        }
        booking.setTotalAmount(total);

        bookingRepository.save(booking);

        // One booking_item per ticket — each carries its own unique ticket
        // number (the QR credential a gate scanner validates). GA tickets have
        // no assigned seat, so seatId is a synthetic per-ticket UUID (keeps the
        // column non-null and the legacy uq_active_booking_item_per_seat index
        // trivially satisfied) and row/seat labels are GA placeholders.
        //
        // Issued in REQUEST order (not grouped by category) so ticket i is the
        // one the purchaser described at seats[i] — that is the only way the
        // attendee they named for it lands on the right ticket.
        List<BookingItem> items = new ArrayList<>(ticketSpecs.size());
        int ticketIndex = 1;
        for (TicketSpec spec : ticketSpecs) {
            CategoryLookupDTO category = categoryById.get(spec.categoryId());
            // One price per ticket of the category, in request order: the
            // allocator returned exactly qtyByCategory's count for it.
            PriceTierAllocator.TicketPrice price = pricesByCategory.get(spec.categoryId()).next();
            items.add(BookingItem.builder()
                    .booking(booking)
                    .seatId(UUID.randomUUID())
                    .categoryId(spec.categoryId())
                    .rowLabel("GA")
                    .seatNumber(ticketIndex++)
                    .categoryName(category.getName())
                    .priceAtBooking(price.price())
                    .priceTierId(price.tierId())
                    .priceTierName(price.tierName())
                    .ticketNumber(references.nextTicketNumber())
                    .attendeeName(spec.attendeeName())
                    .attendeeEmail(spec.attendeeEmail())
                    .attendeePhone(spec.attendeePhone())
                    .build());
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

    /**
     * The counts a category's open, limited tiers need, read under its stock
     * lock (see persistBooking): tickets counting against each tier's quantity
     * (held or sold, plus refunded where refunds do not reopen), and the
     * tickets this buyer already holds at each capped tier, by the purchaser's
     * phone, else email.
     */
    private void countTiers(UUID categoryId, PriceTierAllocator.Counts toCount, Booking booking,
                            Map<UUID, Long> counted, Map<UUID, Long> customer) {
        if (!toCount.quantityTiers().isEmpty()) {
            bookingItemRepository.countActiveByCategoryAndPriceTiers(
                            categoryId, toCount.quantityTiers(), Booking.BookingStatus.CANCELLED)
                    .forEach(row -> counted.merge(row.getPriceTierId(), nz(row.getCount()), Long::sum));
        }
        if (!toCount.keepRefundedTiers().isEmpty()) {
            bookingItemRepository.countRefundedByCategoryAndPriceTiers(categoryId, toCount.keepRefundedTiers())
                    .forEach(row -> counted.merge(row.getPriceTierId(), nz(row.getCount()), Long::sum));
        }
        if (!toCount.customerCappedTiers().isEmpty()) {
            List<BookingItemRepository.TierActiveItemCount> rows;
            if (booking.getPhoneNumber() != null && !booking.getPhoneNumber().isBlank()) {
                rows = bookingItemRepository.countActiveForPhoneByCategoryAndPriceTiers(categoryId,
                        toCount.customerCappedTiers(), booking.getPhoneNumber(), Booking.BookingStatus.CANCELLED);
            } else if (booking.getUserEmail() != null && !booking.getUserEmail().isBlank()) {
                rows = bookingItemRepository.countActiveForEmailByCategoryAndPriceTiers(categoryId,
                        toCount.customerCappedTiers(), booking.getUserEmail(), Booking.BookingStatus.CANCELLED);
            } else {
                rows = List.of();
            }
            rows.forEach(row -> customer.put(row.getPriceTierId(), nz(row.getCount())));
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
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
     * The booking row's write lock, then its items, in the caller's
     * transaction. The lock is taken before anything else so a path that goes
     * on to release stock takes the booking row before any counter row (the
     * order {@link StockLedger} documents). The items are one more query:
     * {@code lockById} cannot fetch-join them (Postgres refuses FOR UPDATE on
     * the nullable side of an outer join).
     */
    private Booking lockWithItems(UUID bookingId, String operation) {
        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> {
                    log.warn("{} failed, booking not found bookingId={}", operation, bookingId);
                    return new NotFoundException("Booking not found");
                });
        Hibernate.initialize(booking.getItems());
        return booking;
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
                                                          UUID requesterOrganizerUuid,
                                                          boolean isAdmin) {
        log.debug("Fetching bookings by category categoryId={} requesterOrganizerUuid={} isAdmin={}",
                categoryId, requesterOrganizerUuid, isAdmin);
        var items = bookingItemRepository.findByCategoryIdWithBooking(categoryId);
        if (items.isEmpty()) {
            return List.of();
        }
        if (!isAdmin) {
            UUID eventId = items.get(0).getBooking().getEventId();
            requireEventOwnership(eventId, requesterOrganizerUuid);
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

    // Per-category sibling of getActiveItemCountsByEvents. seat-service calls
    // this to surface a live availableSeats on every category read
    // (availableSeats = totalSeats − count), mirroring how event-service uses
    // the event-level counts. Categories with no active (PENDING + CONFIRMED)
    // items are absent from the result — the caller reads a missing category
    // as "full capacity remaining".
    public List<CategoryActiveCountDTO> getActiveItemCountsByCategories(Collection<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        return bookingItemRepository
                .countActiveItemsByCategoryIds(categoryIds, Booking.BookingStatus.CANCELLED)
                .stream()
                .map(p -> CategoryActiveCountDTO.builder()
                        .categoryId(p.getCategoryId())
                        .count(p.getCount() == null ? 0L : p.getCount())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Active tickets per price tier, for seat-service's display of each tier's
     * sold / remaining. Display only: a booking counts its tiers itself, under
     * the category's stock lock (persistBooking).
     */
    public List<CategoryTierCountDTO> getActiveTierCountsByCategories(Collection<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        Map<List<UUID>, CategoryTierCountDTO> rows = new LinkedHashMap<>();
        for (BookingItemRepository.CategoryTierActiveItemCount p : bookingItemRepository
                .countActiveItemsByCategoryAndPriceTier(categoryIds, Booking.BookingStatus.CANCELLED)) {
            rows.computeIfAbsent(List.of(p.getCategoryId(), p.getPriceTierId()), k -> CategoryTierCountDTO.builder()
                    .categoryId(p.getCategoryId()).priceTierId(p.getPriceTierId()).build())
                    .setCount(nz(p.getCount()));
        }
        for (BookingItemRepository.CategoryTierActiveItemCount p : bookingItemRepository
                .countRefundedItemsByCategoryAndPriceTier(categoryIds)) {
            rows.computeIfAbsent(List.of(p.getCategoryId(), p.getPriceTierId()), k -> CategoryTierCountDTO.builder()
                    .categoryId(p.getCategoryId()).priceTierId(p.getPriceTierId()).build())
                    .setRefunded(nz(p.getCount()));
        }
        return new ArrayList<>(rows.values());
    }

    // Same shape as getBookingsByCategory but scoped to a whole event. Lets
    // seat-service build event-level analytics in one round trip instead of
    // calling per-category.
    //
    // Ownership: callers other than SUPER_ADMIN must own the event.
    public List<CategoryBookingDTO> getBookingsByEvent(UUID eventId,
                                                       UUID requesterOrganizerUuid,
                                                       boolean isAdmin) {
        log.debug("Fetching bookings by event eventId={} requesterOrganizerUuid={} isAdmin={}",
                eventId, requesterOrganizerUuid, isAdmin);
        if (!isAdmin) {
            requireEventOwnership(eventId, requesterOrganizerUuid);
        }
        return bookingItemRepository.findByEventIdWithBooking(eventId)
                .stream()
                .map(this::toCategoryBookingDTO)
                .collect(Collectors.toList());
    }

    /**
     * Looks up the event in event-service and throws AccessDeniedException if
     * its {@code tenantUserUuid} doesn't match the requester's organizer uuid
     * (the stable cross-service organizer identifier, NOT the email).
     * SUPER_ADMIN callers bypass this check at the controller layer and never
     * reach here.
     *
     * <p>An event whose {@code tenantUserUuid} is still null (pre-V6 row that
     * the backfill runner hasn't caught up on) fails closed — better to make
     * the organizer wait one backfill tick than to silently allow the first
     * EVENT_ORGANIZER through.
     */
    private void requireEventOwnership(UUID eventId, UUID requesterOrganizerUuid) {
        if (requesterOrganizerUuid == null) {
            log.warn("Event ownership check refused: missing organizerUuid on caller token eventId={}", eventId);
            throw new org.springframework.security.access.AccessDeniedException(
                    "Your session is missing organizer information. Please sign in again.");
        }
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing analytics for eventId={} to non-admin",
                    eventId);
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot verify event ownership");
        }
        try {
            // Internal variant — the public endpoint strips tenantUserUuid for
            // anonymous (= server-side) callers, which made this check always
            // see null and 403 every organizer.
            var lookup = client.getEventInternal(eventId, eventInternalToken);
            if (lookup == null || lookup.getData() == null) {
                throw new org.springframework.security.access.AccessDeniedException("Event not found");
            }
            UUID ownerTenantUserUuid = lookup.getData().getTenantUserUuid();
            if (ownerTenantUserUuid == null || !ownerTenantUserUuid.equals(requesterOrganizerUuid)) {
                log.warn("Event ownership check failed eventId={} requesterOrganizerUuid={} ownerTenantUserUuid={}",
                        eventId, requesterOrganizerUuid, ownerTenantUserUuid);
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
                .customerName(b.getCustomerName())
                .phoneNumber(b.getPhoneNumber())
                .attendeeName(item.getAttendeeName())
                .attendeeEmail(item.getAttendeeEmail())
                .attendeePhone(item.getAttendeePhone())
                .holderName(item.holderName())
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
                .priceTierName(item.getPriceTierName())
                .bookedAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .expiresAt(b.getExpiresAt())
                .build();
    }

    public List<BookingResponseDTO> getMyBookings(String userEmail) {
        log.debug("Fetching bookings userEmail={}", userEmail);
        return loadWithItems(() -> bookingRepository.findByUserEmail(userEmail))
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Phone-number lookup (most-recent first). One phone may have many
    // bookings; the caller decides how to render the list.
    public List<BookingResponseDTO> getByPhoneNumber(String phoneNumber) {
        log.debug("Fetching bookings phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        return loadWithItems(() -> bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber))
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Returns only CONFIRMED bookings for a phone number — excludes PENDING and
    // CANCELLED so customers see only paid, valid tickets.
    public List<BookingResponseDTO> getActiveByPhoneNumber(String phoneNumber) {
        log.debug("Fetching confirmed bookings phoneNumber={}", MsisdnMasking.mask(phoneNumber));
        return loadWithItems(() -> bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber))
                .stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.CONFIRMED)
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * The customer's own ticket wallet: CONFIRMED bookings for a phone number,
     * each enriched with its event and classified UPCOMING / LIVE / PAST.
     *
     * <p>Serves the PUBLIC {@code GET /bookings/public/phone/{phoneNumber}}, so
     * it maps to the PII-free {@link CustomerTicketDTO} rather than reusing
     * {@link #toDTO} — see that DTO for what is deliberately withheld.
     *
     * <p><b>One event lookup per DISTINCT event, not per booking.</b> A
     * customer with six tickets to the same festival costs one call, not six.
     * Failures are memoized too (a {@code null} is cached), so an event-service
     * outage costs at most one attempt per event rather than one per row —
     * without that, this endpoint would amplify an outage into a per-booking
     * retry storm against a service that is already struggling.
     *
     * @param filter the bucket to return, or {@code null} for all of them
     */
    public List<CustomerTicketDTO> getPublicTicketsByPhoneNumber(String phoneNumber, TicketWindow filter) {
        // Items loaded in loadWithItems' short transaction; the event lookups
        // below are remote calls and run after it has closed.
        List<Booking> confirmed = loadWithItems(() -> bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber))
                .stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.CONFIRMED)
                .toList();
        log.debug("Public ticket lookup phoneNumber={} confirmed={} filter={}",
                MsisdnMasking.mask(phoneNumber), confirmed.size(), filter);

        // containsKey/put rather than computeIfAbsent: the latter does not cache
        // a null mapping, so a failed lookup would be retried for every booking
        // on that event.
        Map<UUID, EventLookupDTO> eventCache = new HashMap<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<CustomerTicketDTO> tickets = new ArrayList<>();
        for (Booking booking : confirmed) {
            UUID eventId = booking.getEventId();
            EventLookupDTO event = null;
            if (eventId != null) {
                if (!eventCache.containsKey(eventId)) {
                    eventCache.put(eventId, lookupEvent(eventId));
                }
                event = eventCache.get(eventId);
            }

            LocalDateTime start = event == null ? null : event.getStartDateTime();
            LocalDateTime end = event == null ? null : event.getEndDateTime();
            TicketWindow window = TicketWindow.classify(start, end, now);
            if (filter != null && window != filter) {
                continue;
            }

            tickets.add(CustomerTicketDTO.builder()
                    .id(booking.getId())
                    .confirmationNumber(booking.getConfirmationNumber())
                    .eventId(eventId)
                    .eventTitle(event == null ? null : event.getTitle())
                    .venue(event == null ? null : event.getVenue())
                    .startDateTime(start)
                    .endDateTime(end)
                    .window(window)
                    .live(window == TicketWindow.LIVE)
                    .totalAmount(booking.getTotalAmount())
                    // PUBLIC surface: attendee NAME only, never their contact.
                    .items(toItemDTOs(booking, false))
                    .createdAt(booking.getCreatedAt())
                    .build());
        }

        tickets.sort(TICKET_ORDER);
        return tickets;
    }

    /**
     * Runs a bookings query in a short read-only transaction and initialises
     * every result's LAZY {@code items} before it closes, so the caller maps
     * (QR rendering) and calls out with no connection held and no lazy load
     * left to fail. The first collection touched loads the items of up to
     * {@code hibernate.default_batch_fetch_size} bookings in ONE query, so a
     * list costs two statements however many bookings it holds (one more per
     * further 100). Not a fetch join, so it stays correct if the query is ever
     * paged.
     */
    private List<Booking> loadWithItems(java.util.function.Supplier<List<Booking>> query) {
        return Objects.requireNonNullElse(readTx.execute(status -> {
            List<Booking> bookings = query.get();
            bookings.forEach(b -> org.hibernate.Hibernate.initialize(b.getItems()));
            return bookings;
        }), List.of());
    }

    /**
     * Reading order for the ticket wallet: what the customer needs RIGHT NOW
     * first. Live events lead, then upcoming ones soonest-first (the next thing
     * you must show at a gate), then past ones most-recent-first (history, read
     * backwards). A ticket whose event could not be resolved has no start time
     * and sorts to the end of its bucket rather than jumping the queue.
     */
    private static final Comparator<CustomerTicketDTO> TICKET_ORDER = (a, b) -> {
        int byBucket = Integer.compare(a.getWindow().ordinal(), b.getWindow().ordinal());
        if (byBucket != 0) {
            // LIVE(1) must lead UPCOMING(0), so invert the declaration order.
            return a.getWindow() == TicketWindow.LIVE ? -1
                    : b.getWindow() == TicketWindow.LIVE ? 1 : byBucket;
        }
        LocalDateTime sa = a.getStartDateTime();
        LocalDateTime sb = b.getStartDateTime();
        if (sa == null || sb == null) {
            return sa == sb ? 0 : (sa == null ? 1 : -1);
        }
        return a.getWindow() == TicketWindow.PAST ? sb.compareTo(sa) : sa.compareTo(sb);
    };

    /**
     * Item projection with the scannable QR.
     *
     * @param includeAttendeeContact true on the AUTHENTICATED booking views
     *        (owner/admin/organizer-scoped), where the attendee's email/phone
     *        belong; false on the PUBLIC ones (magic-link, phone wallet), which
     *        get the attendee NAME only — the name is on the ticket face, the
     *        contact is PII an enumerating caller must not harvest.
     */
    private List<BookingItemDTO> toItemDTOs(Booking booking, boolean includeAttendeeContact) {
        return booking.getItems() == null
                ? List.of()
                : booking.getItems().stream()
                        .map(i -> BookingItemDTO.builder()
                                .seatId(i.getSeatId())
                                .categoryId(i.getCategoryId())
                                .categoryName(i.getCategoryName())
                                .rowLabel(i.getRowLabel())
                                .seatNumber(i.getSeatNumber())
                                .priceAtBooking(i.getPriceAtBooking())
                                .priceTierName(i.getPriceTierName())
                                .ticketNumber(i.getTicketNumber())
                                .qrCode(qrCodeGenerator.toDataUri(i.getTicketNumber()))
                                .attendeeName(i.getAttendeeName())
                                .attendeeEmail(includeAttendeeContact ? i.getAttendeeEmail() : null)
                                .attendeePhone(includeAttendeeContact ? i.getAttendeePhone() : null)
                                .build())
                        .collect(Collectors.toList());
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail) {
        return getBookingById(bookingId, userEmail, false);
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail, boolean isAdmin) {
        log.debug("Fetching booking bookingId={} userEmail={} isAdmin={}", bookingId, userEmail, isAdmin);
        Booking booking = bookingRepository.findByIdWithItems(bookingId)
                .orElseThrow(() -> {
                    log.warn("Booking lookup failed, not found bookingId={}", bookingId);
                    return new NotFoundException("Booking not found");
                });

        if (!isAdmin && (booking.getUserEmail() == null || !booking.getUserEmail().equals(userEmail))) {
            log.warn("Booking access denied bookingId={} requesterEmail={} ownerEmail={}",
                    bookingId, userEmail, booking.getUserEmail());
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        return toDTO(booking);
    }

    /**
     * Owner-scoped lookup by confirmation number. Mirrors the ownership pattern
     * of {@link #getBookingById(UUID, String, boolean)} but keyed by the
     * confirmation number and fail-quiet: a CUSTOMER who does not own the
     * booking gets a 404 ({@link NotFoundException}), NOT a 403 — the
     * confirmation number is a low-entropy identifier printed on tickets, so a
     * distinct "exists but forbidden" response would let a non-owner confirm
     * that a given confirmation number is real. Admins (EVENT_ORGANIZER /
     * SUPER_ADMIN, {@code isAdmin=true}) bypass the ownership check for support.
     *
     * @param callerEmail    the caller's JWT email (principal); may be null
     * @param callerPhoneE164 the caller's JWT phone already canonicalised to
     *                        E.164 by the controller; may be null
     */
    public BookingResponseDTO getByConfirmationNumber(String confirmationNumber,
                                                      String callerEmail,
                                                      String callerPhoneE164,
                                                      boolean isAdmin) {
        log.debug("Fetching booking by confirmation confirmation={} isAdmin={}",
                confirmationNumber, isAdmin);
        Booking booking = bookingRepository.findByConfirmationNumberWithItems(confirmationNumber)
                .orElseThrow(() -> {
                    log.warn("Booking lookup by confirmation failed, not found confirmation={}",
                            confirmationNumber);
                    return new NotFoundException("Booking not found");
                });

        if (!isAdmin && !ownedByCaller(booking, callerEmail, callerPhoneE164)) {
            // 404, not 403: don't reveal that this confirmation number exists to
            // a caller who doesn't own the booking.
            log.warn("Booking confirmation lookup denied — caller not owner confirmation={} bookingId={}",
                    confirmationNumber, booking.getId());
            throw new NotFoundException("Booking not found");
        }

        return toDTO(booking);
    }

    /**
     * True when the JWT-identified caller owns {@code booking}. Email match
     * mirrors {@link #getBookingById(UUID, String, boolean)} (exact equals on
     * the booking's stored userEmail). Phone match is the fallback for guest
     * bookings that carry no email — both sides are compared in canonical E.164
     * (the booking's phone is stored normalised at creation; the caller's is
     * normalised by the controller before it reaches here).
     */
    private boolean ownedByCaller(Booking booking, String callerEmail, String callerPhoneE164) {
        if (callerEmail != null && !callerEmail.isBlank()
                && callerEmail.equals(booking.getUserEmail())) {
            return true;
        }
        return callerPhoneE164 != null && !callerPhoneE164.isBlank()
                && callerPhoneE164.equals(booking.getPhoneNumber());
    }

    /**
     * Public lookup by id — same shape as {@link #getByConfirmationNumber} but
     * keyed by the booking's UUID. NO ownership check (the UUID is the bearer
     * credential, same model as the public {@code GET /bookings/confirmation/}
     * and the hosted ticket-QR endpoint). Distinct from the JWT-gated
     * {@link #getBookingById(UUID, String, boolean)} above which does scope by
     * owner email — that one stays as-is for the authenticated lookup.
     */
    public PublicBookingResponseDTO getBookingByIdPublic(UUID bookingId) {
        log.debug("Public fetch by id bookingId={}", bookingId);
        return toPublicDTO(bookingRepository.findByIdWithItems(bookingId)
                .orElseThrow(() -> {
                    log.warn("Public booking lookup by id failed, not found bookingId={}", bookingId);
                    return new com.innbucks.bookingservice.exception.NotFoundException("Booking not found");
                }));
    }

    /**
     * PII-free projection for the public {@code GET /bookings/public/{id}}
     * lookup. Drops userEmail / phoneNumber / tenantUserUuid / pointsUsed /
     * cashAmount / updatedAt vs {@link #toDTO}; keeps the tickets (+ QR) so a
     * magic-link view can still render the e-tickets.
     */
    private PublicBookingResponseDTO toPublicDTO(Booking booking) {
        // The ticket number + QR are the gate-entry credential. Only a CONFIRMED
        // (paid) booking may expose them: a PENDING (unpaid, still-held) or
        // CANCELLED booking still renders publicly (status, amount, seats) but
        // withholds the scannable credential, so an unpaid/void booking can never
        // produce a live ticket via the by-UUID magic link. Mirrors the CONFIRMED
        // gate already enforced by TicketController / the ticket-scan path.
        boolean confirmed = booking.getStatus() == Booking.BookingStatus.CONFIRMED;
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
                            .priceTierName(i.getPriceTierName())
                            .ticketNumber(confirmed ? i.getTicketNumber() : null)
                            .qrCode(confirmed ? qrCodeGenerator.toDataUri(i.getTicketNumber()) : null)
                            // Name only — public view; attendee contact stays server-side.
                            .attendeeName(i.getAttendeeName())
                            .build())
                  .collect(Collectors.toList());

        return PublicBookingResponseDTO.builder()
                .id(booking.getId())
                .eventId(booking.getEventId())
                .confirmationNumber(booking.getConfirmationNumber())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .items(itemDTOs)
                .createdAt(booking.getCreatedAt())
                .expiresAt(booking.getExpiresAt())
                .build();
    }

    @Transactional
    public BookingResponseDTO cancelBooking(UUID bookingId, String userEmail) {
        return cancelBooking(bookingId, userEmail, false);
    }

    @Transactional
    public BookingResponseDTO cancelBooking(UUID bookingId, String userEmail, boolean isAdmin) {
        log.info("Cancelling booking bookingId={} userEmail={} isAdmin={}", bookingId, userEmail, isAdmin);
        Booking booking = lockWithItems(bookingId, "Cancel");

        if (!isAdmin && (booking.getUserEmail() == null || !booking.getUserEmail().equals(userEmail))) {
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

        // A payment instrument may still be live (extend-hold stamped how long
        // the last one stays payable). Cancelling now would release the
        // tickets under it, and a payment landing afterwards is money taken
        // with the stock gone. Refused until the window closes; the hold then
        // expires by itself if nobody pays. Read under the row lock, which
        // extend-hold takes too, so the stamp cannot move between this check
        // and the write below.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime paymentOpenUntil = booking.getPaymentOpenUntil();
        if (paymentOpenUntil != null && paymentOpenUntil.isAfter(now)) {
            long retryAfterSeconds = secondsUntil(now, paymentOpenUntil);
            log.warn("Cancel rejected, a payment may still land bookingId={} paymentOpenUntil={} retryAfterSeconds={}",
                    bookingId, paymentOpenUntil, retryAfterSeconds);
            throw new ApiProblemException(HttpStatus.CONFLICT, PAYMENT_IN_PROGRESS, PAYMENT_IN_PROGRESS_MESSAGE,
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        // A late payment may still resurrect it (CancelReason). A SUPER_ADMIN
        // cancelling someone's PENDING booking here is recorded the same way:
        // nobody has paid, so a payment that does land should get its ticket.
        booking.setCancelReason(Booking.CancelReason.CUSTOMER_CANCELLED);
        booking.setExpiresAt(null); // hold no longer applicable
        bookingRepository.save(booking);

        // Return the held tickets to their category counters. Runs once: a
        // booking can only transition PENDING -> CANCELLED here (the guards
        // above reject an already-CANCELLED booking, read under the row lock),
        // so no double-release.
        stockLedger.release(booking);

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
     *
     * <p><b>No transaction is open during the event-service call.</b> It used
     * to run inside this method's {@code @Transactional}, holding a pooled
     * connection for as long as event-service took to answer. Now:
     * <ol>
     *   <li>a short read-only transaction loads and validates the booking (404 /
     *       409 exactly as before) and remembers its {@code @Version};</li>
     *   <li>the release call runs with no transaction — on failure nothing has
     *       been written, as before;</li>
     *   <li>one write transaction LOCKS the booking row (before any counter
     *       row, the order {@link StockLedger} documents), refuses with the
     *       same {@code ObjectOptimisticLockingFailureException} the old
     *       commit-time version check threw if ANY change landed since step 1
     *       (a 409 now), then releases the category counters, sets the flag,
     *       flips to CANCELLED and publishes {@code BookingCancelled}.</li>
     * </ol>
     * The version check is what keeps this as strong as the single transaction
     * was: that one read the row without a lock too, and its only protection
     * against a concurrent change between the read and the write was the
     * optimistic version on the UPDATE. Step 3 applies the same comparison
     * against the version step 1 read, so a reversal racing a reversal (or
     * anything else that bumps the version) still loses with the same
     * exception, and the window in which a successful remote release can be
     * left without its local flip is unchanged: the same rare manual-fix case
     * the comment below describes.
     */
    public BookingResponseDTO reverseConfirmedBooking(UUID bookingId, String adminEmail) {
        log.info("Reversing confirmed booking bookingId={} adminEmail={}", bookingId, adminEmail);
        Booking snapshot = readTx.execute(status -> {
            Booking booking = bookingRepository.findByIdWithItems(bookingId)
                    .orElseThrow(() -> {
                        log.warn("Reverse failed, booking not found bookingId={}", bookingId);
                        return new NotFoundException("Booking not found");
                    });
            if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
                // Caller can only reverse a CONFIRMED booking. PENDING uses /cancel;
                // already-CANCELLED is a no-op the caller shouldn't be hitting.
                log.warn("Reverse rejected, booking not confirmed bookingId={} status={}",
                        bookingId, booking.getStatus());
                throw new BookingConflictException(
                        "This booking can't be reversed at its current stage. Only CONFIRMED bookings can be reversed.");
            }
            return booking;
        });

        if (!snapshot.isAvailabilityReleased()) {
            // Event-service release FIRST (remote, may throw → admin retries
            // with nothing yet mutated), with no transaction open. Then, in the
            // write transaction below, the local category-counter release. Both
            // guarded by the one-shot availabilityReleased flag.
            // Known rare edge: if the write transaction failed after the remote
            // release succeeded, a retry would hit the event-service over-cap
            // clamp; that's a manual-fix case for this rare admin path (a
            // follow-up can add a per-booking release ledger to make both
            // idempotent).
            releaseEventAvailability(snapshot); // throws on failure → admin retries → no partial state
        } else {
            log.info("Skipping release call, already released bookingId={}", bookingId);
        }

        BookingResponseDTO reversed = txTemplate.execute(status -> {
            Booking booking = bookingRepository.lockById(bookingId)
                    .orElseThrow(() -> new NotFoundException("Booking not found"));
            if (!Objects.equals(booking.getVersion(), snapshot.getVersion())) {
                // Changed since the read above — the same refusal the old
                // single transaction's version-checked UPDATE gave. Compared
                // under the row lock, so nothing can change it between this
                // check and the write below.
                log.warn("Reverse lost a race, booking changed during the release call bookingId={}", bookingId);
                throw new ObjectOptimisticLockingFailureException(Booking.class, bookingId);
            }
            Hibernate.initialize(booking.getItems());
            if (!booking.isAvailabilityReleased()) {
                stockLedger.release(booking);
                booking.setAvailabilityReleased(true);
            }
            booking.setStatus(Booking.BookingStatus.CANCELLED);
            // Never resurrected: the money is being returned.
            booking.setCancelReason(Booking.CancelReason.ADMIN_REVERSED);
            bookingRepository.save(booking);

            eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));
            // Rendered before the commit, as it always was, so the response is
            // unchanged (updatedAt is still the value read, not the flush's).
            return toDTO(booking);
        });

        log.info("Booking reversed bookingId={} adminEmail={}", bookingId, adminEmail);
        return reversed;
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

    /**
     * Extend a PENDING booking's seat hold to AT LEAST {@code holdUntil} —
     * called S2S by payment-service just before it mints EVERY payment
     * instrument (an InnBucks code, an EcoCash prompt, a card checkout), so
     * the hold provably outlives what the customer is about to pay with. Never
     * SHORTENS a hold: {@code expiresAt = max(expiresAt, holdUntil)}.
     *
     * <p><b>The payment window.</b> The same call stamps
     * {@code paymentOpenUntil = max(existing, holdUntil)}: because payment-service
     * asks before every instrument, a stamp in the future reliably means "a
     * payment may still land", which is what the customer cancel refuses on.
     *
     * <p><b>Two caps, both refusals, never clamps.</b> A 409 here makes
     * payment-service refuse the payment before anything is minted, so the
     * customer rebooks with no money moved. Clamping the hold and answering 200
     * would let payment-service mint an instrument that outlives the hold —
     * the paid-but-no-ticket hole these caps exist to keep closed.
     * <ul>
     *   <li>{@code holdUntil} more than {@code app.booking.max-hold-extension}
     *       (PT45M) from now: a sanity bound above the longest rail (card, 33
     *       min). Counted {@code max_per_call}, logged at WARN, alerted
     *       ({@code HoldExtensionRefused}): a rail's TTL went up without this.
     *       Checked first, because it is a property of the request alone: a
     *       misconfigured rail on a booking that is also old must still show
     *       up as the config fault it is.</li>
     *   <li>{@code holdUntil} past {@code created_at +
     *       app.booking.max-total-hold} (PT60M, D26): stops a hold being
     *       renewed for ever by re-minting codes without paying. Counted
     *       {@code max_total}.</li>
     * </ul>
     *
     * <p>The booking row is locked first ({@link #lockWithItems}), so the
     * extension serialises with the expiry sweep, a cancel and a confirm. A
     * hold whose {@code expiresAt} has passed but which is still PENDING is
     * extended: its tickets stay claimed until the sweep commits, and confirm
     * honours it too. One the sweep has already cancelled is refused as not
     * PENDING (409), as is a CONFIRMED one.
     */
    @Transactional
    public BookingResponseDTO extendHold(UUID bookingId, LocalDateTime holdUntil) {
        Objects.requireNonNull(holdUntil, "holdUntil");
        Booking booking = lockWithItems(bookingId, "Extend hold");
        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            log.warn("Hold extension refused, booking not pending bookingId={} status={}",
                    bookingId, booking.getStatus());
            throw new BookingConflictException(
                    "This booking's seat hold can no longer be extended — it's not in a PENDING state.");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Defence-in-depth on top of the DTO's @Future. Bean validation runs
        // only on the @Valid-bound controller call; a past timestamp from any
        // other caller is a bug to fail loudly on, not a quiet no-op.
        if (holdUntil.isBefore(now)) {
            throw new BadRequestException("holdUntil must be in the future.");
        }
        LocalDateTime perCallBound = now.plus(maxHoldExtension);
        if (holdUntil.isAfter(perCallBound)) {
            log.warn("Hold extension refused, more than {} from now — has a payment rail's TTL been raised "
                            + "above app.booking.max-hold-extension? bookingId={} holdUntil={} bound={}",
                    maxHoldExtension, bookingId, holdUntil, perCallBound);
            recordHoldExtensionRefused(BookingMetrics.HoldExtensionRefusal.MAX_PER_CALL);
            throw holdNoLongerExtendable();
        }
        // created_at is stamped by @PrePersist on every booking; a row without
        // one cannot be measured against the cap, so it fails closed.
        LocalDateTime totalCap = booking.getCreatedAt() == null ? null : booking.getCreatedAt().plus(maxTotalHold);
        if (totalCap == null || holdUntil.isAfter(totalCap)) {
            log.info("Hold extension refused, past {} from booking bookingId={} createdAt={} holdUntil={}",
                    maxTotalHold, bookingId, booking.getCreatedAt(), holdUntil);
            recordHoldExtensionRefused(BookingMetrics.HoldExtensionRefusal.MAX_TOTAL);
            throw holdNoLongerExtendable();
        }

        boolean changed = false;
        if (booking.getExpiresAt() == null || booking.getExpiresAt().isBefore(holdUntil)) {
            log.info("Extending seat hold bookingId={} from={} to={}{}", bookingId, booking.getExpiresAt(),
                    holdUntil, booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now)
                            ? " (the hold had lapsed; the sweep had not cancelled it)" : "");
            booking.setExpiresAt(holdUntil);
            changed = true;
        }
        if (booking.getPaymentOpenUntil() == null || booking.getPaymentOpenUntil().isBefore(holdUntil)) {
            booking.setPaymentOpenUntil(holdUntil);
            changed = true;
        }
        if (changed) {
            booking = bookingRepository.save(booking);
        }
        return toDTO(booking);
    }

    /**
     * The 409 every refused extension answers. payment-service maps a 409
     * here to "Your booking has expired" and mints nothing; the wording is
     * the one the lapsed-hold refusal always used.
     */
    private BookingConflictException holdNoLongerExtendable() {
        return new BookingConflictException(
                "Your seat reservation has expired. Please start a new booking and complete payment within "
                        + holdTtlMinutes + " minutes.");
    }

    private void recordHoldExtensionRefused(BookingMetrics.HoldExtensionRefusal reason) {
        BookingMetrics m = metrics;
        if (m != null) {
            m.holdExtensionRefused(reason);
        }
    }

    /** Whole seconds from {@code now} until {@code until}, rounded up (until is after now). */
    static long secondsUntil(LocalDateTime now, LocalDateTime until) {
        Duration left = Duration.between(now, until);
        return left.getNano() > 0 ? left.getSeconds() + 1 : left.getSeconds();
    }

    public BookingResponseDTO confirmBooking(UUID bookingId) {
        return confirmBooking(bookingId, null);
    }

    /**
     * Confirm a booking once its payment has landed (S2S, payment-service).
     *
     * <p><b>No method-level transaction.</b> A confirm that finds the booking
     * cancelled may resurrect it, and resurrecting asks seat-service first —
     * with no transaction open, so a pooled connection is never held across
     * that call. Each phase therefore runs in a transaction of its own:
     * <ol>
     *   <li>under the booking row's lock: a PENDING booking is confirmed and
     *       committed right there (one transaction, exactly as before); a
     *       CONFIRMED one is replayed; a CANCELLED one whose
     *       {@link Booking.CancelReason} is resurrectable is handed on, with
     *       the tickets it needs; any other CANCELLED one is refused 409;</li>
     *   <li>with no transaction: seat-service is asked whether each of those
     *       categories still exists. One answered with no data is refused
     *       (409); seat-service unreachable is a 503 — and so, today, is a
     *       deleted category, whose 404 the Feign fallback reports as an
     *       outage (see {@link #requireCategoriesStillExist}). Either way
     *       payment-service keeps the payment and retries the confirm;</li>
     *   <li>under the booking row's lock again: the tickets are re-claimed
     *       with the same guarded claim a new booking uses, in the canonical
     *       category order ({@link StockLedger}), and the booking is
     *       CONFIRMED. A claim that fails (sold out since) rolls the whole
     *       thing back and answers 409. A booking another confirm resurrected
     *       in the meantime is replayed (no second claim, no second
     *       {@code BookingConfirmed}); one that changed in any other way —
     *       resurrected and then reversed — is refused 409 with nothing
     *       claimed. Pinned by {@code BookingResurrectRecheckTest} and the two
     *       races in {@code BookingPaymentWindowPostgresIT}.</li>
     * </ol>
     * Every phase publishes inside its transaction ({@code BookingConfirmed}
     * listeners are {@code AFTER_COMMIT}, and drop an event published with
     * none open).
     */
    public BookingResponseDTO confirmBooking(UUID bookingId, ConfirmBookingRequestDTO confirmRequest) {
        log.info("Confirming booking bookingId={} pointsToUse={} cashAmount={}",
                bookingId,
                confirmRequest == null ? null : confirmRequest.getPointsToUse(),
                confirmRequest == null ? null : confirmRequest.getCashAmount());

        // Defence-in-depth on top of the DTO's @DecimalMin(0). Bean validation
        // fires only on the @Valid-bound controller call, so an S2S caller (or
        // a direct service caller in a future code path) could otherwise sneak
        // a negative cashAmount or pointsToUse through. The split-math check
        // in applyLoyalty re-validates the SUM but not the individual signs —
        // a negative cash + a positive points still passes when the math adds
        // up to totalAmount, but it isn't a payment we should accept.
        if (confirmRequest != null) {
            if (confirmRequest.getPointsToUse() != null
                    && confirmRequest.getPointsToUse().signum() < 0) {
                throw new BadRequestException("pointsToUse must be >= 0.");
            }
            if (confirmRequest.getCashAmount() != null
                    && confirmRequest.getCashAmount().signum() < 0) {
                throw new BadRequestException("cashAmount must be >= 0.");
            }
        }

        ConfirmStep step = Objects.requireNonNull(
                txTemplate.execute(status -> confirmUnderLock(bookingId, confirmRequest)));
        if (step.resurrection() == null) {
            return step.response();
        }
        return resurrect(bookingId, confirmRequest, step.resurrection());
    }

    /**
     * Phase 1 of a confirm: what the booking row says, under its lock. Either
     * the confirm's answer, or a {@link Resurrection} still to attempt.
     */
    private record ConfirmStep(BookingResponseDTO response, Resurrection resurrection) {
        static ConfirmStep answered(BookingResponseDTO response) {
            return new ConfirmStep(response, null);
        }
    }

    /**
     * A cancelled booking a late payment may bring back: why it was
     * cancelled, and its tickets per category in the canonical lock order.
     * Record equality is what phase 3 re-checks against the locked row.
     */
    private record Resurrection(Booking.CancelReason reason, SortedMap<UUID, Integer> ticketsByCategory) {
        static Resurrection of(Booking booking) {
            SortedMap<UUID, Integer> tickets = StockLedger.byCategory();
            if (booking.getItems() != null) {
                for (BookingItem item : booking.getItems()) {
                    tickets.merge(item.getCategoryId(), 1, Integer::sum);
                }
            }
            return new Resurrection(booking.getCancelReason(), Collections.unmodifiableSortedMap(tickets));
        }
    }

    private ConfirmStep confirmUnderLock(UUID bookingId, ConfirmBookingRequestDTO confirmRequest) {
        // The row lock serialises confirm with the expiry sweep, a cancel and
        // a second confirm: whoever holds it decides, and the others see what
        // it decided once it commits.
        Booking booking = lockWithItems(bookingId, "Confirm");

        // CONFIRMED: idempotent replay. payment-service's Idempotency-Key cache
        // holds the original response for 24h, but a retry that lands beyond
        // that window — or arrives from a different replica that lost its
        // in-memory cache — would otherwise see status != PENDING and 409 even
        // though the booking IS confirmed. False-rejection surfaces to the
        // customer as "your booking failed" right after a successful confirm.
        // Returning the existing DTO is the correct retry semantics.
        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            log.info("Confirm idempotent replay (already CONFIRMED) bookingId={}", bookingId);
            return ConfirmStep.answered(toDTO(booking));
        }
        // CANCELLED: the payment landed after the booking was cancelled — by
        // the expiry sweep, the buyer, or an admin. The first two (and a buyer
        // release) may come back if the stock is still there; refusing them
        // leaves a buyer who paid with no ticket. An admin reversal never
        // does, and neither does a booking cancelled before V25, whose reason
        // nobody recorded.
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            Booking.CancelReason reason = booking.getCancelReason();
            if (reason != null && reason.resurrectable()) {
                log.info("Confirm found a cancelled booking a late payment may resurrect bookingId={} reason={}",
                        bookingId, reason);
                return new ConfirmStep(null, Resurrection.of(booking));
            }
            log.warn("Confirm rejected, booking cancelled for good bookingId={} cancelReason={}",
                    bookingId, reason);
            throw new BookingConflictException(NOT_CONFIRMABLE_MESSAGE);
        }
        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            log.warn("Confirm rejected, booking not pending bookingId={} status={}",
                    bookingId, booking.getStatus());
            throw new BookingConflictException(NOT_CONFIRMABLE_MESSAGE);
        }

        // A hold whose expiresAt has passed but which is still PENDING is
        // HONOURED. Its tickets stay claimed until the sweep commits the
        // cancellation, and the sweep takes this same row lock (skipping a row
        // that is held), so confirming here cannot oversell: either the sweep
        // committed first and the booking is CANCELLED above, or this confirm
        // holds the row and the sweep leaves it alone. Refusing it used to
        // strand a customer who paid a few seconds late with money taken and
        // no ticket.
        if (booking.getExpiresAt() != null
                && booking.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            log.info("Confirming a lapsed hold the sweep has not cancelled bookingId={} expiredAt={}",
                    bookingId, booking.getExpiresAt());
        }
        return ConfirmStep.answered(markConfirmed(booking, confirmRequest));
    }

    /**
     * Phases 2 and 3 of a confirm (see {@link #confirmBooking}). Counted on
     * {@code booking.confirm.resurrected{reason,outcome}}: {@code failed} for
     * every refusal and error after the decision to try — the buyer has paid
     * and has no ticket until a retry succeeds — and {@code succeeded} once
     * the booking is CONFIRMED. A replay (another confirm got there first) is
     * neither.
     */
    private BookingResponseDTO resurrect(UUID bookingId, ConfirmBookingRequestDTO confirmRequest,
                                         Resurrection resurrection) {
        Booking.CancelReason reason = resurrection.reason();
        try {
            requireCategoriesStillExist(bookingId, resurrection);
            Resurrected result = Objects.requireNonNull(txTemplate.execute(status ->
                    resurrectUnderLock(bookingId, confirmRequest, resurrection)));
            if (!result.replay()) {
                recordResurrect(reason, BookingMetrics.ResurrectOutcome.SUCCEEDED);
            }
            return result.response();
        } catch (RuntimeException refused) {
            recordResurrect(reason, BookingMetrics.ResurrectOutcome.FAILED);
            throw refused;
        }
    }

    private record Resurrected(BookingResponseDTO response, boolean replay) {
    }

    /**
     * Phase 2: seat-service still lists every category the booking needs. No
     * transaction is open (pinned by {@code RemoteCallsOutsideTransactionTest}).
     * A category answered with no data is gone — refused 409, like any booking
     * that cannot be confirmed.
     *
     * <p>Note what the Feign client makes of seat-service's own 404 for a
     * soft-deleted category: its fallback discards the status and raises
     * {@link com.innbucks.bookingservice.exception.SeatServiceUnavailableException},
     * exactly as for an outage. That propagates as a 503, which payment-service
     * also retries, and the refusal is counted the same way; telling "deleted"
     * from "down" apart needs a fallback factory that keeps the status, a
     * change to every seat-service call.
     */
    private void requireCategoriesStillExist(UUID bookingId, Resurrection resurrection) {
        for (UUID categoryId : resurrection.ticketsByCategory().keySet()) {
            ApiResult<CategoryLookupDTO> envelope = seatServiceClient.getCategory(categoryId);
            if (envelope == null || envelope.getData() == null) {
                log.warn("Resurrect refused, category no longer exists bookingId={} categoryId={} reason={}",
                        bookingId, categoryId, resurrection.reason());
                throw new BookingConflictException(NOT_CONFIRMABLE_MESSAGE);
            }
        }
    }

    /**
     * Phase 3, under the booking row's lock: re-claim the tickets and confirm.
     * Takes the booking row, then the counter rows in ascending category
     * order — the global lock order — and touches nothing else. Uses confirm's
     * guard (capacity only), not the claim's: the buyer has already paid.
     */
    private Resurrected resurrectUnderLock(UUID bookingId, ConfirmBookingRequestDTO confirmRequest,
                                           Resurrection resurrection) {
        Booking booking = lockWithItems(bookingId, "Confirm");
        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            log.info("Confirm idempotent replay (resurrected by a concurrent confirm) bookingId={}", bookingId);
            return new Resurrected(toDTO(booking), true);
        }
        if (booking.getStatus() != Booking.BookingStatus.CANCELLED
                || !resurrection.equals(Resurrection.of(booking))) {
            // Changed since phase 1 read it: another confirm resurrected it
            // and an admin reversed it (a refund) while this one asked
            // seat-service. Claim nothing on a row phase 1 did not decide on;
            // a retry starts afresh and is refused for the reversal.
            log.warn("Resurrect refused, booking changed since it was read bookingId={} status={} cancelReason={}",
                    bookingId, booking.getStatus(), booking.getCancelReason());
            throw new BookingConflictException(NOT_CONFIRMABLE_MESSAGE);
        }
        for (Map.Entry<UUID, Integer> tickets : resurrection.ticketsByCategory().entrySet()) {
            // The counter row exists: this booking's own claim created or used
            // it, and rows are never deleted. A missing one claims nothing and
            // is refused, never seeded.
            if (categoryInventoryRepository.tryClaim(tickets.getKey(), tickets.getValue()) == 0) {
                log.warn("Resurrect refused, not enough tickets left bookingId={} categoryId={} qty={} reason={}",
                        bookingId, tickets.getKey(), tickets.getValue(), resurrection.reason());
                // Rolls back every claim made above for this booking.
                throw new BookingConflictException(NOT_CONFIRMABLE_MESSAGE);
            }
        }
        // CONFIRMED with its reason cleared: the row reads as if the payment
        // had landed in time. The V5 trigger (sync_booking_items_is_active)
        // re-activates the items on the CANCELLED -> CONFIRMED status change.
        booking.setCancelReason(null);
        BookingResponseDTO confirmed = markConfirmed(booking, confirmRequest);
        log.info("Booking resurrected bookingId={} reason={} tickets={}",
                bookingId, resurrection.reason(), resurrection.ticketsByCategory());
        return new Resurrected(confirmed, false);
    }

    private void recordResurrect(Booking.CancelReason reason, BookingMetrics.ResurrectOutcome outcome) {
        BookingMetrics m = metrics;
        if (m != null) {
            m.resurrected(reason, outcome);
        }
    }

    /**
     * The PENDING (or resurrected) booking becomes CONFIRMED, in the caller's
     * transaction, under the row lock the caller holds.
     */
    private BookingResponseDTO markConfirmed(Booking booking, ConfirmBookingRequestDTO confirmRequest) {
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

        // The event-service availableTickets decrement used to be a Feign call
        // made here, inside this transaction — a pooled connection held across
        // a remote call for a best-effort mirror. It now runs AFTER COMMIT,
        // off this thread, from EventAvailabilityConsumeListener on the event
        // below; publishing only here (never on a replay) is what keeps it to
        // one decrement per real confirmation. A resurrected booking was never
        // decremented: only a confirm decrements, and the cancellation that
        // preceded it was of an unpaid booking.
        eventPublisher.publishEvent(BookingDomainEvent.BookingConfirmed.of(booking));

        log.info("Booking confirmed bookingId={} userEmail={} pointsUsed={} cashAmount={}",
                booking.getId(), booking.getUserEmail(), booking.getPointsUsed(), booking.getCashAmount());
        return toDTO(booking);
    }

    // Loyalty is DECOUPLED from booking (product decision): a ticket purchase
    // NEVER earns loyalty points and can NOT be paid for with points. Points
    // accrue ONLY via shop-checkout / guest-shop-checkout in loyalty-service.
    //
    // This used to validate a points/cash split and call redeem+earn on
    // loyalty-service (which is what awarded points — and fired the "points
    // earned" WhatsApp notification — on every ticket purchase). All of that
    // linkage is removed. The only remaining job is to reject an attempt to pay
    // with points, so a booking is never confirmed for points that were never
    // actually redeemed (which would hand out a ticket for nothing).
    private void applyLoyalty(Booking booking, BigDecimal pointsToUse, BigDecimal cashAmount) {
        if (pointsToUse != null && pointsToUse.signum() > 0) {
            throw new BadRequestException("Paying for tickets with loyalty points isn't available.");
        }
        // Cash-only: no loyalty earn on ticket purchases. Nothing to do.
    }

    /**
     * Best-effort event lookup. Returns the {@link EventLookupDTO} verbatim
     * so callers can read every field we care about (tenantUserUuid for both
     * loyalty attribution and scan authorization) without a second
     * cross-service call. Returns null on any failure — every consumer
     * treats null as "feature degrades gracefully" rather than blocking
     * booking creation.
     */
    /**
     * The event's static fields for booking creation (organizer uuid capture)
     * and the public ticket list. Served from {@link EventLookupCache} when one
     * is wired — see it for what may be stale and for how long. NOT the path
     * for an ownership check ({@link #requireEventOwnership} calls
     * event-service itself, every time).
     */
    private EventLookupDTO lookupEvent(UUID eventId) {
        EventLookupCache cache = eventLookupCache;
        return cache == null ? fetchEventLookup(eventId) : cache.get(eventId, () -> fetchEventLookup(eventId));
    }

    /**
     * The reference generator ({@link BookingReferences}). A setter for the
     * same reason as the cache below; without it the instance's own default
     * (SecureRandom, UTC clock) mints them.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferences(BookingReferences references) {
        this.references = Objects.requireNonNull(references, "references");
    }

    /**
     * Setter, not a constructor argument, so the many hand-built instances in
     * tests keep their constructor; without it every lookup goes live.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEventLookupCache(EventLookupCache eventLookupCache) {
        this.eventLookupCache = eventLookupCache;
    }

    /** Setter for the same reason; without it the claim retry is simply not counted. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(BookingMetrics metrics) {
        this.metrics = metrics;
    }

    // The two hold caps are parsed here rather than injected as Duration: a
    // @Value Duration needs Spring Boot's conversion service on the bean
    // factory, which a context built without SpringApplication (several tests
    // build one) does not have. Both ISO-8601 (PT60M) and the simple form
    // (60m) are accepted, as for any Boot Duration property.
    @org.springframework.beans.factory.annotation.Value("${app.booking.max-total-hold:PT60M}")
    void setMaxTotalHold(String value) {
        this.maxTotalHold = org.springframework.boot.convert.DurationStyle.detectAndParse(value);
    }

    @org.springframework.beans.factory.annotation.Value("${app.booking.max-hold-extension:PT45M}")
    void setMaxHoldExtension(String value) {
        this.maxHoldExtension = org.springframework.boot.convert.DurationStyle.detectAndParse(value);
    }

    private EventLookupDTO fetchEventLookup(UUID eventId) {
        EventServiceClient event = eventClientProvider == null ? null : eventClientProvider.getIfAvailable();
        if (event == null) {
            return null;
        }
        try {
            // Internal variant: the public GET /events/{id} strips tenantUserUuid
            // for anonymous callers, and this server-side call is anonymous — the
            // whole point of this lookup is capturing that field onto the booking.
            ApiResult<EventLookupDTO> envelope = event.getEventInternal(eventId, eventInternalToken);
            return envelope == null ? null : envelope.getData();
        } catch (Exception ex) {
            log.warn("Failed to fetch event lookup eventId={} reason={}", eventId, ex.getMessage());
            return null;
        }
    }

    private BookingResponseDTO toDTO(Booking booking) {
        // Authenticated/owner view: the full attendee record travels.
        List<BookingItemDTO> itemDTOs = toItemDTOs(booking, true);

        return BookingResponseDTO.builder()
                .id(booking.getId())
                .userEmail(booking.getUserEmail())
                .customerName(booking.getCustomerName())
                .phoneNumber(booking.getPhoneNumber())
                .eventId(booking.getEventId())
                .tenantUserUuid(booking.getTenantUserUuid())
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
