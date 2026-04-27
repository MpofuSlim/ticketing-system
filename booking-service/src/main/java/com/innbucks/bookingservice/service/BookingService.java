package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.*;
import com.innbucks.bookingservice.entity.*;
import com.innbucks.bookingservice.exception.TierRequirementException;
import com.innbucks.bookingservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final SeatServiceClient seatServiceClient;

    private static final int TIER_2_MAX_SEATS = 2;
    private static final int TIER_3_MAX_SEATS = 6;
    private static final int TIER_4_MAX_SEATS = 10;

    @Transactional
    public BookingResponseDTO createBooking(
            String userEmail,
            int tier,
            CreateBookingRequestDTO request
    ) {
        log.info("Creating booking userEmail={} tier={} eventId={} seats={}",
                userEmail, tier, request.getEventId(), request.getSeats().size());

        int maxSeats = maxSeatsForTier(tier);
        if (request.getSeats().size() > maxSeats) {
            log.warn("Booking rejected, exceeds tier seat limit userEmail={} tier={} requested={} max={}",
                    userEmail, tier, request.getSeats().size(), maxSeats);
            throw new TierRequirementException(
                    "Tier " + tier + " customers may book at most " + maxSeats + " seats per booking");
        }

        List<UUID> requestedSeatIds = request.getSeats().stream()
                .map(CreateBookingRequestDTO.SeatItemRequest::getSeatId)
                .toList();
        List<BookingItem> existingActive = bookingItemRepository.findActiveBySeatIds(
                requestedSeatIds, Booking.BookingStatus.CANCELLED);
        if (!existingActive.isEmpty()) {
            List<UUID> clashingSeats = existingActive.stream()
                    .map(BookingItem::getSeatId)
                    .distinct()
                    .toList();
            log.warn("Booking create rejected, seats already booked userEmail={} seatIds={}",
                    userEmail, clashingSeats);
            throw new RuntimeException("One or more seats are already booked: " + clashingSeats);
        }

        // Resolve every seat against seat-service so price, category, and event
        // are derived from the source of truth rather than client input.
        // The eventId and categoryId on the request are kept as defensive
        // cross-checks — they must match what the seat actually belongs to.
        List<SeatLookupResponseDTO> resolved = new ArrayList<>(requestedSeatIds.size());
        for (CreateBookingRequestDTO.SeatItemRequest item : request.getSeats()) {
            SeatLookupResponseDTO seat = lookupSeat(item.getSeatId());
            if (!request.getEventId().equals(seat.getEventId())) {
                log.warn("Booking rejected, seat does not belong to event userEmail={} seatId={} requestEventId={} actualEventId={}",
                        userEmail, item.getSeatId(), request.getEventId(), seat.getEventId());
                throw new RuntimeException(
                        "Seat " + item.getSeatId() + " does not belong to event " + request.getEventId());
            }
            if (!item.getCategoryId().equals(seat.getCategoryId())) {
                log.warn("Booking rejected, seat does not belong to category userEmail={} seatId={} requestCategoryId={} actualCategoryId={}",
                        userEmail, item.getSeatId(), item.getCategoryId(), seat.getCategoryId());
                throw new RuntimeException(
                        "Seat " + item.getSeatId() + " does not belong to category " + item.getCategoryId());
            }
            resolved.add(seat);
        }

        BigDecimal total = resolved.stream()
                .map(SeatLookupResponseDTO::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String confirmationNumber = generateConfirmationNumber();

        Booking booking = Booking.builder()
                .userEmail(userEmail)
                .eventId(request.getEventId())
                .confirmationNumber(confirmationNumber)
                .status(Booking.BookingStatus.PENDING)
                .totalAmount(total)
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

        log.info("Booking created bookingId={} confirmation={} userEmail={} eventId={} total={} items={}",
                booking.getId(), confirmationNumber, userEmail, request.getEventId(),
                total, items.size());
        return toDTO(booking);
    }

    public List<BookingResponseDTO> getMyBookings(String userEmail) {
        log.debug("Fetching bookings userEmail={}", userEmail);
        return bookingRepository.findByUserEmail(userEmail)
                .stream()
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
        bookingRepository.save(booking);

        log.info("Booking cancelled bookingId={} userEmail={}", bookingId, userEmail);
        return toDTO(booking);
    }

    @Transactional
    public BookingResponseDTO confirmBooking(UUID bookingId) {
        log.info("Confirming booking bookingId={}", bookingId);
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

        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        log.info("Booking confirmed bookingId={} userEmail={}", bookingId, booking.getUserEmail());
        return toDTO(booking);
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
                            .build())
                  .collect(Collectors.toList());

        return BookingResponseDTO.builder()
                .id(booking.getId())
                .userEmail(booking.getUserEmail())
                .eventId(booking.getEventId())
                .confirmationNumber(booking.getConfirmationNumber())
                .status(booking.getStatus())
                .totalAmount(booking.getTotalAmount())
                .items(itemDTOs)
                .createdAt(booking.getCreatedAt())
                .updatedAt(booking.getUpdatedAt())
                .build();
    }
}
