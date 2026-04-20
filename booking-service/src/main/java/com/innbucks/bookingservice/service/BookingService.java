package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.*;
import com.innbucks.bookingservice.entity.*;
import com.innbucks.bookingservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;

    @Transactional
    public BookingResponseDTO createBooking(
            String userEmail,
            CreateBookingRequestDTO request
    ) {
        BigDecimal total = request.getSeats().stream()
                .map(CreateBookingRequestDTO.SeatItemRequest::getPriceAtBooking)
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
        List<BookingItem> items = request.getSeats().stream()
                .map(s -> BookingItem.builder()
                        .booking(booking)
                        .seatId(s.getSeatId())
                        .categoryId(s.getCategoryId())
                        .rowLabel(s.getRowLabel())
                        .seatNumber(s.getSeatNumber())
                        .categoryName(s.getCategoryName())
                        .priceAtBooking(s.getPriceAtBooking())
                        .ticketNumber(generateTicketNumber()) // unique per seat
                        .build())
                .collect(Collectors.toList());

        bookingItemRepository.saveAll(items);
        booking.setItems(items);
        bookingRepository.save(booking);

        return toDTO(booking);
    }

    public List<BookingResponseDTO> getMyBookings(String userEmail) {
        return bookingRepository.findByUserEmail(userEmail)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public BookingResponseDTO getBookingById(UUID bookingId, String userEmail) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (!booking.getUserEmail().equals(userEmail)) {
            throw new RuntimeException("Access denied");
        }

        return toDTO(booking);
    }

    public BookingResponseDTO getByConfirmationNumber(String confirmationNumber) {
        return toDTO(bookingRepository
                .findByConfirmationNumber(confirmationNumber)
                .orElseThrow(() -> new RuntimeException("Booking not found")));
    }

    @Transactional
    public BookingResponseDTO cancelBooking(UUID bookingId, String userEmail) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (!booking.getUserEmail().equals(userEmail)) {
            throw new RuntimeException("Access denied");
        }

        if (booking.getStatus() == Booking.BookingStatus.CONFIRMED) {
            throw new RuntimeException(
                    "Cannot cancel a confirmed booking — payment already processed"
            );
        }

        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new RuntimeException("Booking is already cancelled");
        }

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        return toDTO(booking);
    }

    @Transactional
    public BookingResponseDTO confirmBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getStatus() != Booking.BookingStatus.PENDING) {
            throw new RuntimeException("Only pending bookings can be confirmed");
        }

        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        return toDTO(booking);
    }

    // ==================== HELPERS ====================

    /**
     * Generates a booking confirmation number
     * Format: INN-20260419-A3F9B2
     */
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
