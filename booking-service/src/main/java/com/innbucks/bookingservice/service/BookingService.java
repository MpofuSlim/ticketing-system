package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.LoyaltyServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.*;
import com.innbucks.bookingservice.entity.*;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.exception.TierRequirementException;
import com.innbucks.bookingservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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
                throw new RuntimeException("No available seats in category " + categoryId);
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
            throw new RuntimeException("One or more seats are already booked: " + clashingSeats);
        }

        // Resolve full details (price, eventId, sectionLabel) from seat-service.
        // The available-seats endpoint omits these fields by design.
        List<SeatLookupResponseDTO> resolved = new ArrayList<>(assignedSeatIds.size());
        for (UUID seatId : assignedSeatIds) {
            SeatLookupResponseDTO seat = lookupSeat(seatId);
            if (!request.getEventId().equals(seat.getEventId())) {
                log.warn("Booking rejected, category does not belong to event userEmail={} seatId={} requestEventId={} actualEventId={}",
                        userEmail, seatId, request.getEventId(), seat.getEventId());
                throw new RuntimeException(
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
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(holdTtlMinutes);

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
    public List<CategoryBookingDTO> getBookingsByCategory(UUID categoryId) {
        log.debug("Fetching bookings by category categoryId={}", categoryId);
        return bookingItemRepository.findByCategoryIdWithBooking(categoryId)
                .stream()
                .map(this::toCategoryBookingDTO)
                .collect(Collectors.toList());
    }

    // Returns the count of active (PENDING + CONFIRMED) booking items per
    // eventId. event-service uses this to compute availableTickets on read so
    // its responses tally with reality, without maintaining a stored counter.
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
    public List<CategoryBookingDTO> getBookingsByEvent(UUID eventId) {
        log.debug("Fetching bookings by event eventId={}", eventId);
        return bookingItemRepository.findByEventIdWithBooking(eventId)
                .stream()
                .map(this::toCategoryBookingDTO)
                .collect(Collectors.toList());
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
        log.debug("Fetching bookings phoneNumber={}", phoneNumber);
        return bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // Returns only CONFIRMED bookings for a phone number — excludes PENDING and
    // CANCELLED so customers see only paid, valid tickets.
    public List<BookingResponseDTO> getActiveByPhoneNumber(String phoneNumber) {
        log.debug("Fetching confirmed bookings phoneNumber={}", phoneNumber);
        return bookingRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber)
                .stream()
                .filter(b -> b.getStatus() == Booking.BookingStatus.CONFIRMED)
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail) {
        log.debug("Fetching booking bookingId={} userEmail={}", bookingId, userEmail);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Booking lookup failed, not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        if (!booking.getUserEmail().equals(userEmail)) {
            log.warn("Booking access denied bookingId={} requesterEmail={} ownerEmail={}",
                    bookingId, userEmail, booking.getUserEmail());
            throw new RuntimeException("Access denied");
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
        log.info("Cancelling booking bookingId={} userEmail={}", bookingId, userEmail);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> {
                    log.warn("Cancel failed, booking not found bookingId={}", bookingId);
                    return new RuntimeException("Booking not found");
                });

        if (!booking.getUserEmail().equals(userEmail)) {
            log.warn("Cancel rejected, access denied bookingId={} requesterEmail={} ownerEmail={}",
                    bookingId, userEmail, booking.getUserEmail());
            throw new RuntimeException("Access denied");
        }

        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            log.warn("Cancel rejected, booking already confirmed bookingId={}", bookingId);
            throw new RuntimeException(
                    "Cannot cancel a confirmed booking — payment already processed"
            );
        }

        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            log.warn("Cancel rejected, booking already cancelled bookingId={}", bookingId);
            throw new RuntimeException("Booking is already cancelled");
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setExpiresAt(null); // hold no longer applicable
        bookingRepository.save(booking);

        eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));

        log.info("Booking cancelled bookingId={} userEmail={}", bookingId, userEmail);
        return toDTO(booking);
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

        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            log.warn("Confirm rejected, booking not pending bookingId={} status={}",
                    bookingId, booking.getStatus());
            throw new RuntimeException("Only pending bookings can be confirmed");
        }

        // Reject confirms that arrive after the seat hold has lapsed — the
        // expiration scheduler may not have run yet, but the lock is gone.
        if (booking.getExpiresAt() != null
                && booking.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Confirm rejected, hold expired bookingId={} expiredAt={}",
                    bookingId, booking.getExpiresAt());
            throw new RuntimeException(
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
                throw new RuntimeException("Cannot redeem points — booking has no tenant attached");
            }
            log.debug("No tenantId on booking; skipping loyalty earn for bookingId={}", booking.getId());
            return;
        }

        LoyaltyRuleResponse rule = fetchRule(loyalty, booking.getTenantId());
        if (rule == null) {
            if (pointsToUse.signum() > 0) {
                throw new RuntimeException("Loyalty rule unavailable; cannot redeem points right now");
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
            throw new RuntimeException(String.format(
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
                // Earning is best-effort; never fail a paid booking because we
                // couldn't credit points. The redeem (if any) already succeeded
                // and is idempotent on retry.
                log.warn("Loyalty earn failed bookingId={} cashAmount={} reason={}",
                        booking.getId(), cashAmount, ex.getMessage());
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
            throw new RuntimeException("Seat " + seatId + " not found");
        }
        return envelope.getData();
    }

    private List<UUID> fetchAvailableSeatIds(UUID categoryId) {
        ApiResult<List<AvailableSeatDTO>> envelope = seatServiceClient.getAvailableSeats(categoryId);
        if (envelope == null || envelope.getData() == null || envelope.getData().isEmpty()) {
            log.warn("No available seats returned by seat-service categoryId={}", categoryId);
            throw new RuntimeException("No available seats in category " + categoryId);
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
        String date = LocalDateTime.now()
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
     */
    private String generateTicketNumber() {
        String date = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Random random = new Random();

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            digits.append(random.nextInt(10));
        }

        char letter = (char) ('A' + random.nextInt(26));

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
