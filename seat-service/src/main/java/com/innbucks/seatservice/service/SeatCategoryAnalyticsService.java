package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.SeatCategoryAnalyticsDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatCategoryAnalyticsService {

    private final SeatCategoryRepository categoryRepository;
    private final SeatRepository seatRepository;
    private final BookingServiceClient bookingServiceClient;

    @Transactional(readOnly = true)
    public SeatCategoryAnalyticsDTO getAnalytics(UUID categoryId, String authHeader) {
        log.debug("Building analytics categoryId={}", categoryId);

        SeatCategory category = categoryRepository.findById(categoryId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> {
                    log.warn("Analytics requested for unknown categoryId={}", categoryId);
                    return new RuntimeException("Seat category not found");
                });

        SeatCategoryAnalyticsDTO.SeatStatusCounts seatCounts = countSeatStatuses(categoryId);

        Optional<List<CategoryBookingDTO>> bookings =
                bookingServiceClient.fetchBookingsByCategory(categoryId, authHeader);
        boolean reachable = bookings.isPresent();

        SeatCategoryAnalyticsDTO.BookingStats stats = bookings
                .map(items -> buildBookingStats(items, category))
                .orElseGet(() -> emptyStats(category));

        return SeatCategoryAnalyticsDTO.builder()
                .category(toCategoryInfo(category))
                .seatStatusCounts(seatCounts)
                .bookings(stats)
                .bookingServiceReachable(reachable)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    private SeatCategoryAnalyticsDTO.CategoryInfo toCategoryInfo(SeatCategory c) {
        return SeatCategoryAnalyticsDTO.CategoryInfo.builder()
                .id(c.getId())
                .eventId(c.getEventId())
                .name(c.getName())
                .description(c.getDescription())
                .price(c.getPrice())
                .totalSeats(c.getTotalSeats())
                .cachedAvailableSeats(c.getAvailableSeats())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    // Counts each Seat.SeatStatus exactly once by streaming the seats table.
    // For categories with thousands of seats this is fine — JPA loads them
    // batched; the analytics endpoint is not on a hot path.
    private SeatCategoryAnalyticsDTO.SeatStatusCounts countSeatStatuses(UUID categoryId) {
        Map<Seat.SeatStatus, Long> grouped = seatRepository.findByCategoryId(categoryId)
                .stream()
                .collect(Collectors.groupingBy(Seat::getStatus, Collectors.counting()));
        long available = grouped.getOrDefault(Seat.SeatStatus.AVAILABLE, 0L);
        long locked = grouped.getOrDefault(Seat.SeatStatus.LOCKED, 0L);
        long booked = grouped.getOrDefault(Seat.SeatStatus.BOOKED, 0L);
        return SeatCategoryAnalyticsDTO.SeatStatusCounts.builder()
                .total(available + locked + booked)
                .available(available)
                .locked(locked)
                .booked(booked)
                .build();
    }

    private SeatCategoryAnalyticsDTO.BookingStats buildBookingStats(
            List<CategoryBookingDTO> items, SeatCategory category) {
        BigDecimal gross = items.stream()
                .map(CategoryBookingDTO::getPriceAtBooking)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = items.stream()
                .filter(b -> b.getStatus() != CategoryBookingDTO.BookingStatus.CANCELLED)
                .map(CategoryBookingDTO::getPriceAtBooking)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int cancelled = (int) items.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.CANCELLED)
                .count();
        int active = items.size() - cancelled;
        LocalDateTime mostRecent = items.stream()
                .map(CategoryBookingDTO::getBookedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal potential = category.getPrice() != null
                ? category.getPrice().multiply(BigDecimal.valueOf(category.getTotalSeats()))
                : null;
        List<CategoryBookingDTO> sorted = items.stream()
                .sorted(Comparator.comparing(CategoryBookingDTO::getBookedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return SeatCategoryAnalyticsDTO.BookingStats.builder()
                .totalRecords(items.size())
                .activeRecords(active)
                .cancelledRecords(cancelled)
                .grossRevenue(gross)
                .netRevenue(net)
                .potentialRevenue(potential)
                .mostRecentBookingAt(mostRecent)
                .items(sorted)
                .build();
    }

    private SeatCategoryAnalyticsDTO.BookingStats emptyStats(SeatCategory category) {
        BigDecimal potential = category.getPrice() != null
                ? category.getPrice().multiply(BigDecimal.valueOf(category.getTotalSeats()))
                : null;
        return SeatCategoryAnalyticsDTO.BookingStats.builder()
                .totalRecords(0)
                .activeRecords(0)
                .cancelledRecords(0)
                .grossRevenue(BigDecimal.ZERO)
                .netRevenue(BigDecimal.ZERO)
                .potentialRevenue(potential)
                .mostRecentBookingAt(null)
                .items(List.of())
                .build();
    }
}
