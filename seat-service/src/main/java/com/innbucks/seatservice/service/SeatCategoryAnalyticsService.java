package com.innbucks.seatservice.service;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import com.innbucks.seatservice.dto.EventAnalyticsDTO;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatCategoryAnalyticsService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final SeatCategoryRepository categoryRepository;
    private final SeatRepository seatRepository;
    private final BookingServiceClient bookingServiceClient;
    private final ObjectProvider<EventServiceClient> eventClientProvider;

    @Transactional(readOnly = true)
    public EventAnalyticsDTO getEventAnalytics(UUID eventId,
                                               String requesterEmail,
                                               boolean isAdmin,
                                               int page, int size, String authHeader) {
        if (!isAdmin) {
            requireEventOwnership(eventId, requesterEmail, authHeader);
        }
        log.debug("Building event analytics eventId={} page={} size={}", eventId, page, size);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        List<SeatCategory> categories = categoryRepository.findByEventIdAndDeletedFalse(eventId);

        // One booking-service round trip covers every category in the event.
        Optional<List<CategoryBookingDTO>> allBookings =
                bookingServiceClient.fetchBookingsByEvent(eventId, authHeader);
        boolean reachable = allBookings.isPresent();

        // Group bookings by categoryId so each per-category block sees only
        // its own rows. Missing entries → empty list.
        Map<UUID, List<CategoryBookingDTO>> bookingsByCategory = allBookings
                .orElseGet(List::of)
                .stream()
                .filter(b -> b.getCategoryId() != null)
                .collect(Collectors.groupingBy(CategoryBookingDTO::getCategoryId));

        List<EventAnalyticsDTO.CategoryAnalytics> blocks = new ArrayList<>(categories.size());
        for (SeatCategory category : categories) {
            List<CategoryBookingDTO> categoryBookings =
                    bookingsByCategory.getOrDefault(category.getId(), List.of());
            blocks.add(buildCategoryBlock(category, categoryBookings, reachable, safePage, safeSize));
        }

        EventAnalyticsDTO.EventTotals totals = rollUp(categories, blocks);

        return EventAnalyticsDTO.builder()
                .eventId(eventId)
                .categoryCount(categories.size())
                .totals(totals)
                .categories(blocks)
                .bookingServiceReachable(reachable)
                .fetchedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    /**
     * Defense-in-depth ownership check — analytics expose customer emails and
     * revenue, so a non-admin caller must own the event in event-service. Same
     * pattern as {@link SeatCategoryService#requireEventOwnership}.
     */
    private void requireEventOwnership(UUID eventId, String requesterEmail, String authHeader) {
        EventServiceClient client = eventClientProvider == null
                ? null : eventClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("event-service client unavailable; refusing analytics for eventId={} to non-admin", eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        var lookup = client.fetchEvent(eventId, authHeader);
        if (lookup.isEmpty()) {
            log.warn("Analytics ownership lookup empty eventId={}", eventId);
            throw new AccessDeniedException("Cannot verify event ownership");
        }
        String ownerTenantId = lookup.get().getTenantId();
        if (ownerTenantId == null || !ownerTenantId.equals(requesterEmail)) {
            log.warn("Analytics ownership check failed eventId={} requesterEmail={} ownerTenantId={}",
                    eventId, requesterEmail, ownerTenantId);
            throw new AccessDeniedException("You do not own this event");
        }
    }

    private EventAnalyticsDTO.CategoryAnalytics buildCategoryBlock(
            SeatCategory category, List<CategoryBookingDTO> categoryBookings,
            boolean bookingsReachable, int page, int size) {
        return EventAnalyticsDTO.CategoryAnalytics.builder()
                .category(toCategoryInfo(category))
                .seatStatusCounts(countSeatStatuses(category, categoryBookings, bookingsReachable))
                .bookings(buildBookingStats(categoryBookings, category, page, size))
                .build();
    }

    private EventAnalyticsDTO.CategoryInfo toCategoryInfo(SeatCategory c) {
        return EventAnalyticsDTO.CategoryInfo.builder()
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

    // When booking-service is reachable, derive seat status counts from the
    // booking items so the response reflects live booking state instead of the
    // seat-table mirror (which can drift when seat status flips aren't
    // synchronously applied on confirm/cancel). Falls back to the seats table
    // when booking-service is down.
    private EventAnalyticsDTO.SeatStatusCounts countSeatStatuses(
            SeatCategory category, List<CategoryBookingDTO> categoryBookings, boolean reachable) {
        if (!reachable) {
            Map<Seat.SeatStatus, Long> grouped = seatRepository.findByCategoryId(category.getId())
                    .stream()
                    .collect(Collectors.groupingBy(Seat::getStatus, Collectors.counting()));
            long available = grouped.getOrDefault(Seat.SeatStatus.AVAILABLE, 0L);
            long locked = grouped.getOrDefault(Seat.SeatStatus.LOCKED, 0L);
            long booked = grouped.getOrDefault(Seat.SeatStatus.BOOKED, 0L);
            return EventAnalyticsDTO.SeatStatusCounts.builder()
                    .total(available + locked + booked)
                    .available(available)
                    .locked(locked)
                    .booked(booked)
                    .build();
        }

        long booked = categoryBookings.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.CONFIRMED)
                .map(CategoryBookingDTO::getSeatId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long locked = categoryBookings.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.PENDING)
                .map(CategoryBookingDTO::getSeatId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long total = category.getTotalSeats() == null ? (booked + locked) : category.getTotalSeats();
        long available = Math.max(0L, total - booked - locked);
        return EventAnalyticsDTO.SeatStatusCounts.builder()
                .total(total)
                .available(available)
                .locked(locked)
                .booked(booked)
                .build();
    }

    private EventAnalyticsDTO.BookingStats buildBookingStats(
            List<CategoryBookingDTO> items, SeatCategory category, int page, int size) {
        // Aggregates across the full set so totals don't change as the
        // consumer pages through.
        BigDecimal pending = sumPrices(items, b -> b.getStatus() == CategoryBookingDTO.BookingStatus.PENDING);
        BigDecimal paid    = sumPrices(items, b -> b.getStatus() == CategoryBookingDTO.BookingStatus.CONFIRMED);
        int pendingCount   = (int) items.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.PENDING)
                .count();
        int paidCount      = (int) items.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.CONFIRMED)
                .count();
        int cancelled = (int) items.stream()
                .filter(b -> b.getStatus() == CategoryBookingDTO.BookingStatus.CANCELLED)
                .count();
        LocalDateTime mostRecent = items.stream()
                .map(CategoryBookingDTO::getBookedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal potential = potentialRevenue(category);

        List<CategoryBookingDTO> sorted = items.stream()
                .sorted(Comparator.comparing(CategoryBookingDTO::getBookedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / size);
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        List<CategoryBookingDTO> pageItems = sorted.subList(from, to);

        return EventAnalyticsDTO.BookingStats.builder()
                .totalRecords(items.size())
                .pendingRecords(pendingCount)
                .paidRecords(paidCount)
                .cancelledRecords(cancelled)
                .pendingRevenue(pending)
                .paidRevenue(paid)
                .potentialRevenue(potential)
                .mostRecentBookingAt(mostRecent)
                .pageNumber(page)
                .pageSize(size)
                .totalPages(totalPages)
                .items(pageItems)
                .build();
    }

    private static BigDecimal sumPrices(List<CategoryBookingDTO> items,
                                        java.util.function.Predicate<CategoryBookingDTO> include) {
        return items.stream()
                .filter(include)
                .map(CategoryBookingDTO::getPriceAtBooking)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal potentialRevenue(SeatCategory category) {
        if (category.getPrice() == null || category.getTotalSeats() == null) {
            return null;
        }
        return category.getPrice().multiply(BigDecimal.valueOf(category.getTotalSeats()));
    }

    private EventAnalyticsDTO.EventTotals rollUp(
            List<SeatCategory> categories, List<EventAnalyticsDTO.CategoryAnalytics> blocks) {
        long totalSeats = 0, available = 0, locked = 0, booked = 0;
        int totalBookings = 0, pendingBookings = 0, paidBookings = 0, cancelledBookings = 0;
        BigDecimal pendingRevenue = BigDecimal.ZERO;
        BigDecimal paidRevenue = BigDecimal.ZERO;
        BigDecimal potential = BigDecimal.ZERO;
        LocalDateTime mostRecent = null;

        for (EventAnalyticsDTO.CategoryAnalytics block : blocks) {
            EventAnalyticsDTO.SeatStatusCounts s = block.getSeatStatusCounts();
            totalSeats += s.getTotal();
            available  += s.getAvailable();
            locked     += s.getLocked();
            booked     += s.getBooked();

            EventAnalyticsDTO.BookingStats b = block.getBookings();
            totalBookings     += b.getTotalRecords();
            pendingBookings   += b.getPendingRecords();
            paidBookings      += b.getPaidRecords();
            cancelledBookings += b.getCancelledRecords();
            if (b.getPendingRevenue() != null)   pendingRevenue = pendingRevenue.add(b.getPendingRevenue());
            if (b.getPaidRevenue() != null)      paidRevenue    = paidRevenue.add(b.getPaidRevenue());
            if (b.getPotentialRevenue() != null) potential      = potential.add(b.getPotentialRevenue());
            if (b.getMostRecentBookingAt() != null
                    && (mostRecent == null || b.getMostRecentBookingAt().isAfter(mostRecent))) {
                mostRecent = b.getMostRecentBookingAt();
            }
        }

        return EventAnalyticsDTO.EventTotals.builder()
                .totalSeats(totalSeats)
                .availableSeats(available)
                .lockedSeats(locked)
                .bookedSeats(booked)
                .totalBookings(totalBookings)
                .pendingBookings(pendingBookings)
                .paidBookings(paidBookings)
                .cancelledBookings(cancelledBookings)
                .pendingRevenue(pendingRevenue)
                .paidRevenue(paidRevenue)
                .potentialRevenue(categories.isEmpty() ? null : potential)
                .mostRecentBookingAt(mostRecent)
                .build();
    }
}
