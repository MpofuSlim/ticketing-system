package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.report.BucketSize;
import com.innbucks.bookingservice.dto.report.CategoryRevenueDTO;
import com.innbucks.bookingservice.dto.report.EventRevenueDTO;
import com.innbucks.bookingservice.dto.report.RevenueSummaryDTO;
import com.innbucks.bookingservice.dto.report.SalesBucketDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.innbucks.bookingservice.repository.OrganizerReportRepository;

/**
 * Financial reports for an EVENT_ORGANIZER, scoped to the events they own
 * (Booking.tenantUserUuid == the caller's organizerUuid). Revenue is taken
 * from CONFIRMED bookings (a booking confirms only after payment), with admin
 * reversals (CANCELLED + availabilityReleased) reported as refunds.
 *
 * <p>Aggregation is done in-memory from period-bounded, organizer-scoped
 * fetches — see {@link OrganizerReportRepository} for why money isn't SUMmed
 * over an item join. An organizer's per-period booking volume is bounded
 * (capacity-limited events), so the fetch is modest and the code stays simple
 * and unit-testable.
 */
@Service
@Slf4j
public class OrganizerReportService {

    /** Hard cap on the look-back window to keep an unbounded export honest. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final OrganizerReportRepository repository;
    private final String currency;

    public OrganizerReportService(OrganizerReportRepository repository,
                                  @Value("${innbucks.currency:USD}") String currency) {
        this.repository = repository;
        this.currency = (currency == null || currency.isBlank()) ? "USD" : currency;
    }

    @Transactional(readOnly = true)
    public RevenueSummaryDTO revenueSummary(UUID organizerUuid, UUID eventId, LocalDate from, LocalDate to) {
        Range r = resolveRange(from, to);
        List<Booking> confirmed = repository.findConfirmedWithItems(organizerUuid, eventId, r.start(), r.end());
        List<Booking> reversed = repository.findReversed(organizerUuid, eventId, r.start(), r.end());

        long confirmedBookings = confirmed.size();
        long ticketsSold = 0;
        BigDecimal confirmedTotal = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal points = BigDecimal.ZERO;
        for (Booking b : confirmed) {
            ticketsSold += ticketCount(b);
            confirmedTotal = confirmedTotal.add(nz(b.getTotalAmount()));
            cash = cash.add(nz(b.getCashAmount()));
            points = points.add(nz(b.getPointsUsed()));
        }
        BigDecimal refundedTotal = BigDecimal.ZERO;
        for (Booking b : reversed) {
            refundedTotal = refundedTotal.add(nz(b.getTotalAmount()));
        }
        BigDecimal gross = confirmedTotal.add(refundedTotal);
        BigDecimal net = confirmedTotal;
        BigDecimal aov = confirmedBookings == 0 ? BigDecimal.ZERO
                : net.divide(BigDecimal.valueOf(confirmedBookings), 2, RoundingMode.HALF_UP);

        return new RevenueSummaryDTO(
                r.from(), r.to(), eventId,
                confirmedBookings, ticketsSold,
                scale2(gross), scale2(cash), scale2(points),
                reversed.size(), scale2(refundedTotal),
                scale2(net), aov, currency);
    }

    @Transactional(readOnly = true)
    public List<EventRevenueDTO> revenueByEvent(UUID organizerUuid, LocalDate from, LocalDate to) {
        Range r = resolveRange(from, to);
        List<Booking> confirmed = repository.findConfirmedWithItems(organizerUuid, null, r.start(), r.end());
        List<Booking> reversed = repository.findReversed(organizerUuid, null, r.start(), r.end());

        Map<UUID, long[]> counts = new LinkedHashMap<>();      // eventId -> [bookings, tickets]
        Map<UUID, BigDecimal> confirmedTotals = new LinkedHashMap<>();
        Map<UUID, BigDecimal> refundTotals = new LinkedHashMap<>();
        for (Booking b : confirmed) {
            UUID ev = b.getEventId();
            long[] c = counts.computeIfAbsent(ev, k -> new long[2]);
            c[0]++;
            c[1] += ticketCount(b);
            confirmedTotals.merge(ev, nz(b.getTotalAmount()), BigDecimal::add);
        }
        for (Booking b : reversed) {
            refundTotals.merge(b.getEventId(), nz(b.getTotalAmount()), BigDecimal::add);
        }

        // Union of events that had a confirmed sale OR a refund in the window.
        List<UUID> eventIds = new ArrayList<>(counts.keySet());
        for (UUID ev : refundTotals.keySet()) {
            if (!counts.containsKey(ev)) eventIds.add(ev);
        }

        List<EventRevenueDTO> out = new ArrayList<>();
        for (UUID ev : eventIds) {
            long[] c = counts.getOrDefault(ev, new long[2]);
            BigDecimal confirmedTotal = confirmedTotals.getOrDefault(ev, BigDecimal.ZERO);
            BigDecimal refunded = refundTotals.getOrDefault(ev, BigDecimal.ZERO);
            out.add(new EventRevenueDTO(ev, c[0], c[1],
                    scale2(confirmedTotal.add(refunded)), scale2(refunded), scale2(confirmedTotal)));
        }
        out.sort(Comparator.comparing(EventRevenueDTO::netRevenue).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public List<CategoryRevenueDTO> revenueByCategory(UUID organizerUuid, UUID eventId, LocalDate from, LocalDate to) {
        if (eventId == null) {
            throw new BadRequestException("eventId is required for the per-category breakdown.");
        }
        Range r = resolveRange(from, to);
        List<Booking> confirmed = repository.findConfirmedWithItems(organizerUuid, eventId, r.start(), r.end());

        Map<UUID, CategoryAccumulator> byCategory = new LinkedHashMap<>();
        for (Booking b : confirmed) {
            if (b.getItems() == null) continue;
            for (BookingItem item : b.getItems()) {
                CategoryAccumulator acc = byCategory.computeIfAbsent(item.getCategoryId(),
                        k -> new CategoryAccumulator(item.getCategoryName()));
                acc.tickets++;
                acc.revenue = acc.revenue.add(nz(item.getPriceAtBooking()));
            }
        }
        List<CategoryRevenueDTO> out = new ArrayList<>();
        byCategory.forEach((categoryId, acc) ->
                out.add(new CategoryRevenueDTO(categoryId, acc.name, acc.tickets, scale2(acc.revenue))));
        out.sort(Comparator.comparing(CategoryRevenueDTO::revenue).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public List<SalesBucketDTO> salesTimeSeries(UUID organizerUuid, UUID eventId,
                                                LocalDate from, LocalDate to, BucketSize bucket) {
        Range r = resolveRange(from, to);
        BucketSize size = bucket == null ? BucketSize.DAY : bucket;
        List<Booking> confirmed = repository.findConfirmedWithItems(organizerUuid, eventId, r.start(), r.end());

        // Sorted map keyed by bucket start so the series comes out chronological.
        Map<LocalDate, long[]> counts = new java.util.TreeMap<>();   // [bookings, tickets]
        Map<LocalDate, BigDecimal> totals = new java.util.HashMap<>();
        for (Booking b : confirmed) {
            LocalDate key = bucketStart(b.getCreatedAt().toLocalDate(), size);
            long[] c = counts.computeIfAbsent(key, k -> new long[2]);
            c[0]++;
            c[1] += ticketCount(b);
            totals.merge(key, nz(b.getTotalAmount()), BigDecimal::add);
        }
        List<SalesBucketDTO> out = new ArrayList<>();
        counts.forEach((key, c) ->
                out.add(new SalesBucketDTO(key, c[0], c[1], scale2(totals.getOrDefault(key, BigDecimal.ZERO)))));
        return out;
    }

    /** CSV ledger of the period's CONFIRMED bookings, ordered by createdAt. */
    @Transactional(readOnly = true)
    public String confirmedBookingsCsv(UUID organizerUuid, UUID eventId, LocalDate from, LocalDate to) {
        Range r = resolveRange(from, to);
        List<Booking> confirmed = repository.findConfirmedWithItems(organizerUuid, eventId, r.start(), r.end());

        StringBuilder sb = new StringBuilder();
        sb.append("confirmationNumber,eventId,createdAt,ticketsSold,totalAmount,cashAmount,pointsUsed,phone\n");
        for (Booking b : confirmed) {
            sb.append(csv(b.getConfirmationNumber())).append(',')
              .append(csv(b.getEventId())).append(',')
              .append(b.getCreatedAt() == null ? "" : b.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append(',')
              .append(ticketCount(b)).append(',')
              .append(scale2(nz(b.getTotalAmount()))).append(',')
              .append(scale2(nz(b.getCashAmount()))).append(',')
              .append(scale2(nz(b.getPointsUsed()))).append(',')
              .append(csv(MsisdnMasking.mask(b.getPhoneNumber()))).append('\n');
        }
        return sb.toString();
    }

    /** Validate + default the [from, to] window into a half-open instant range. */
    private Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new BadRequestException("'from' (" + resolvedFrom + ") must not be after 'to' (" + resolvedTo + ").");
        }
        // Half-open [start, end): start at 00:00 of `from`, end at 00:00 of the
        // day AFTER `to`, so the whole `to` day is included.
        return new Range(resolvedFrom, resolvedTo,
                resolvedFrom.atStartOfDay(), resolvedTo.plusDays(1).atStartOfDay());
    }

    private static LocalDate bucketStart(LocalDate day, BucketSize size) {
        return size == BucketSize.WEEK
                ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : day;
    }

    private static int ticketCount(Booking b) {
        return b.getItems() == null ? 0 : b.getItems().size();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Minimal CSV cell escaping: neutralise spreadsheet formula injection (a
     * leading = + - @ / tab / CR is executed as a formula by Excel/Sheets), then
     * quote when the value contains a comma/quote/newline.
     */
    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private record Range(LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end) { }

    private static final class CategoryAccumulator {
        final String name;
        long tickets;
        BigDecimal revenue = BigDecimal.ZERO;
        CategoryAccumulator(String name) { this.name = name; }
    }
}
