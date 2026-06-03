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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final SeatServiceClient seatServiceClient;
    private final ApplicationEventPublisher eventPublisher;
    private final QrCodeGenerator qrCodeGenerator;
    // ObjectProvider so unit tests that build the service with `new` and pass
    // null for the loyalty/event clients still work — the loyalty integration
    // is only exercised by tests that opt in.
    private final ObjectProvider<LoyaltyServiceClient> loyaltyClientProvider;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final LoyaltyEarnRetryService loyaltyEarnRetryService;

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

    @Transactional
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

        // For each requested category, pick a random AVAILABLE seat from
        // seat-service. Cache the available pool per category so multiple
        // seats in the same category share one upstream call, and track
        // already-picked seats so the same seat isn't assigned twice in
        // one request.
        Map<UUID, List<UUID>> availablePoolByCategory = new HashMap<>();
        Set<UUID> pickedInThisRequest = new HashSet<>();
        List<UUID> assignedSeatIds = new ArrayList<>(request.getSeats().size());
        for (CreateBookingRequestDTO.SeatItemRequest item : request.getSeats()) {
            UUID categoryId = item.getCategoryId();
            List<UUID> pool = availablePoolByCategory.computeIfAbsent(
                    categoryId, this::fetchAvailableSeatIds);

            UUID picked = pickRandomAvailable(pool, pickedInThisRequest);
            if (picked == null) {
                log.warn("Booking rejected, no available seats userEmail={} categoryId={}", userEmail, categoryId);
                throw new BadRequestException("No available seats in category " + categoryId);
            }
            pickedInThisRequest.add(picked);
            assignedSeatIds.add(picked);
        }

        // Cross-check against our own DB. Catches the race where a seat that
        // looked AVAILABLE in seat-service is already part of an active
        // booking on our side (e.g. concurrent request just before
        // seat-service was updated).
        List<BookingItem> existingActive = bookingItemRepository.findActiveBySeatIds(
                assignedSeatIds, Booking.BookingStatus.CANCELLED);
        if (!existingActive.isEmpty()) {
            List<UUID> clashingSeats = existingActive.stream()
                    .map(BookingItem::getSeatId)
                    .distinct()
                    .toList();
            log.warn("Booking create rejected, picked seats already booked userEmail={} seatIds={}",
                    userEmail, clashingSeats);
            throw new BookingConflictException("One or more seats are already booked: " + clashingSeats);
        }

        // Resolve full details (price, eventId, sectionLabel) from seat-service.
        // The available-seats endpoint omits these fields by design.
        List<SeatLookupResponseDTO> resolved = new ArrayList<>(assignedSeatIds.size());
        for (UUID seatId : assignedSeatIds) {
            SeatLookupResponseDTO seat = lookupSeat(seatId);
            if (!request.getEventId().equals(seat.getEventId())) {
                log.warn("Booking rejected, category does not belong to event userEmail={} seatId={} requestEventId={} actualEventId={}",
                        userEmail, seatId, request.getEventId(), seat.getEventId());
                throw new BadRequestException(
                        "Category " + seat.getCategoryId() + " does not belong to event " + request.getEventId());
            }
            resolved.add(seat);
        }

        BigDecimal total = resolved.stream()
                .map(SeatLookupResponseDTO::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String confirmationNumber = generateConfirmationNumber();

        // Seats are held for holdTtlMinutes; if no /confirm (= payment) lands
        // by then, the expiration scheduler flips the booking to CANCELLED
        // so the seats are usable by other customers again.
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(holdTtlMinutes);

        // Resolve the owning tenant once at booking creation so the loyalty
        // service can attribute earn/redeem at confirm time without another
        // event-service round trip. Best-effort: an event-service outage
        // doesn't block bookings — the booking just won't earn loyalty
        // points until tenant is known.
        String tenantId = lookupTenantId(request.getEventId());

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

        bookingRepository.save(booking);

        // Each seat gets its own unique ticket number
        List<BookingItem> items = resolved.stream()
                .map(s -> BookingItem.builder()
                        .booking(booking)
                        .seatId(s.getSeatId())
                        .categoryId(s.getCategoryId())
                        .rowLabel(s.getSectionLabel())
                        .seatNumber(s.getSeatNumber())
                        .categoryName(s.getCategoryName())
                        .priceAtBooking(s.getPrice())
                        .ticketNumber(generateTicketNumber()) // unique per seat
                        .build())
                .collect(Collectors.toList());

        bookingItemRepository.saveAll(items);
        booking.setItems(items);
        bookingRepository.save(booking);

        eventPublisher.publishEvent(BookingDomainEvent.BookingCreated.of(booking, assignedSeatIds));

        log.info("Booking created bookingId={} confirmation={} userEmail={} eventId={} total={} items={}",
                booking.getId(), confirmationNumber, userEmail, request.getEventId(),
                total, items.size());
        return toDTO(booking);
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
                    "Cannot cancel a confirmed booking — payment already processed"
            );
        }

        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            log.warn("Cancel rejected, booking already cancelled bookingId={}", bookingId);
            throw new BookingConflictException("Booking is already cancelled");
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setExpiresAt(null); // hold no longer applicable
        bookingRepository.save(booking);

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
                    "Cannot reverse booking with status " + booking.getStatus()
                            + " — only CONFIRMED bookings can be reversed");
        }

        if (!booking.isAvailabilityReleased()) {
            releaseEventAvailability(booking); // throws on failure → admin retries → no partial state
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
            throw new BookingConflictException("Only pending bookings can be confirmed");
        }

        // Reject confirms that arrive after the seat hold has lapsed — the
        // expiration scheduler may not have run yet, but the lock is gone.
        if (booking.getExpiresAt() != null
                && booking.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            log.warn("Confirm rejected, hold expired bookingId={} expiredAt={}",
                    bookingId, booking.getExpiresAt());
            throw new BookingConflictException(
                    "Seat hold expired — please create a new booking and pay within "
                            + holdTtlMinutes + " minutes");
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
                throw new BadRequestException("Cannot redeem points — booking has no tenant attached");
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
                    "Payment split does not match total: points worth %s + cash %s = %s, expected %s",
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

    private SeatLookupResponseDTO lookupSeat(UUID seatId) {
        ApiResult<SeatLookupResponseDTO> envelope = seatServiceClient.lookupSeat(seatId);
        if (envelope == null || envelope.getData() == null) {
            log.warn("Seat lookup returned no data seatId={}", seatId);
            throw new NotFoundException("Seat " + seatId + " not found");
        }
        return envelope.getData();
    }

    private List<UUID> fetchAvailableSeatIds(UUID categoryId) {
        ApiResult<List<AvailableSeatDTO>> envelope = seatServiceClient.getAvailableSeats(categoryId);
        if (envelope == null || envelope.getData() == null || envelope.getData().isEmpty()) {
            log.warn("No available seats returned by seat-service categoryId={}", categoryId);
            throw new BadRequestException("No available seats in category " + categoryId);
        }
        return envelope.getData().stream()
                .map(AvailableSeatDTO::getId)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // Returns a uniformly random seatId from the pool, skipping any already
    // claimed by an earlier item in the same booking request. Returns null if
    // the pool has no candidates left.
    private UUID pickRandomAvailable(List<UUID> pool, Set<UUID> alreadyPicked) {
        List<UUID> candidates = pool.stream()
                .filter(id -> !alreadyPicked.contains(id))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /**
     * Generates a booking confirmation number
     * Format: INN-20260419-A3F9B2
     */
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
