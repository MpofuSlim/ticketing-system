package com.innbucks.bookingservice.numbering;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.NumberSeriesRepository;
import com.innbucks.bookingservice.service.StockLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hands out assigned numbers (V34) — "Bib T014". Always inside the caller's
 * transaction:
 *
 * <ul>
 *   <li>{@link #assignOnConfirm}: from {@code BookingService.markConfirmed},
 *       under the booking row's lock. A ticket is numbered when it is PAID for,
 *       never when it is only held.</li>
 *   <li>{@link #assignSold}: from {@code assign-sold}, the paid tickets of a
 *       category that have none (numbering added after sales began).</li>
 * </ul>
 *
 * <p><b>Lock order.</b> Series rows are locked after the booking row (and, on a
 * resurrect, after the counter rows), in {@link StockLedger#CATEGORY_ORDER},
 * and nothing that holds a series row then takes a booking or counter row, so
 * the global order still holds. {@code assign-sold} takes the series row
 * alone.
 *
 * <p>Numbers are never reused: the counter only grows, a refunded ticket keeps
 * its number, and every ticket is written once ({@code assigned_number IS
 * NULL}).
 */
@Slf4j
@Component
public class TicketNumberAssigner {

    /** Tickets written per statement; the arrays are bound as one literal each. */
    static final int WRITE_BATCH = 500;

    private final NumberSeriesRepository series;

    public TicketNumberAssigner(NumberSeriesRepository series) {
        this.series = series;
    }

    /**
     * Numbers the booking's tickets that have none, for every category of it
     * that numbers its tickets: in the order the buyer listed them. One look
     * up for a booking with no numbered category; a claim and a write per
     * numbered category otherwise. The items' in-memory fields are filled too,
     * so the confirm's own answer carries the numbers.
     */
    public void assignOnConfirm(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        List<BookingItem> unnumbered = items.stream().filter(i -> i.getAssignedNumber() == null).toList();
        if (unnumbered.isEmpty()) {
            return;
        }
        Set<UUID> categories = unnumbered.stream().map(BookingItem::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        if (categories.isEmpty()) {
            return;
        }
        Set<UUID> numbered = new HashSet<>(series.findNumberedCategoryIds(categories));
        if (numbered.isEmpty()) {
            return;
        }
        Map<UUID, List<BookingItem>> byCategory = new TreeMap<>(StockLedger.CATEGORY_ORDER);
        unnumbered.stream()
                .filter(i -> numbered.contains(i.getCategoryId()))
                .sorted(Comparator.comparing((BookingItem i) -> i.getSeatNumber() == null ? Integer.MAX_VALUE
                        : i.getSeatNumber()).thenComparing(i -> String.valueOf(i.getId())))
                .forEach(i -> byCategory.computeIfAbsent(i.getCategoryId(), k -> new ArrayList<>()).add(i));
        for (Map.Entry<UUID, List<BookingItem>> e : byCategory.entrySet()) {
            Block block = claim(e.getKey(), e.getValue().size());
            if (block == null) {
                continue; // removed since the look (it had assigned nothing): nothing to number
            }
            List<UUID> ids = e.getValue().stream().map(BookingItem::getId).toList();
            List<String> numbers = block.numbers();
            write(ids, numbers, block.label());
            for (int k = 0; k < e.getValue().size(); k++) {
                e.getValue().get(k).setAssignedNumber(numbers.get(k));
                e.getValue().get(k).setNumberLabel(block.label());
            }
            log.info("Assigned numbers bookingId={} categoryId={} count={}",
                    booking.getId(), e.getKey(), numbers.size());
        }
    }

    /**
     * Numbers every paid ticket of the category that has none, continuing the
     * series. The series row is locked first, so no confirm claims a block
     * between the read of the candidates and the claim. Returns how many were
     * numbered; empty when the category has no series.
     */
    public java.util.OptionalInt assignSold(UUID categoryId) {
        if (series.lockSeries(categoryId).isEmpty()) {
            return java.util.OptionalInt.empty();
        }
        List<UUID> ids = series.findUnnumberedPaidItemIds(categoryId);
        if (ids.isEmpty()) {
            return java.util.OptionalInt.of(0);
        }
        Block block = Objects.requireNonNull(claim(categoryId, ids.size()), "series locked above");
        write(ids, block.numbers(), block.label());
        log.info("Assigned numbers to tickets already sold categoryId={} count={}", categoryId, ids.size());
        return java.util.OptionalInt.of(ids.size());
    }

    private Block claim(UUID categoryId, int count) {
        List<Object[]> rows = series.claimBlock(categoryId, count, LocalDateTime.now(ZoneOffset.UTC));
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        long first = ((Number) row[0]).longValue();
        String label = (String) row[1];
        String prefix = (String) row[2];
        int padding = ((Number) row[3]).intValue();
        List<String> numbers = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            numbers.add(NumberFormat.render(prefix, first + k, padding));
        }
        return new Block(label, numbers);
    }

    private void write(List<UUID> ids, List<String> numbers, String label) {
        for (int from = 0; from < ids.size(); from += WRITE_BATCH) {
            int to = Math.min(ids.size(), from + WRITE_BATCH);
            List<UUID> idChunk = ids.subList(from, to);
            List<String> numberChunk = numbers.subList(from, to);
            int written = series.writeNumbers(arrayLiteral(idChunk.stream().map(UUID::toString).toList()),
                    arrayLiteral(numberChunk), label);
            if (written != idChunk.size()) {
                // Every caller holds the lock that stops a second writer, so
                // this would be a ticket numbered behind it. Fail the whole
                // transaction rather than leave a number claimed twice or not
                // at all.
                throw new IllegalStateException("Assigned numbers written to " + written + " of "
                        + idChunk.size() + " tickets");
            }
        }
    }

    /** {@code {a,b,c}}: ids and numbers hold only letters, digits and hyphens, so nothing needs quoting. */
    static String arrayLiteral(List<String> values) {
        for (String v : values) {
            if (!v.matches("[A-Za-z0-9-]+")) {
                throw new IllegalArgumentException("Not an array-safe value");
            }
        }
        return "{" + String.join(",", values) + "}";
    }

    private record Block(String label, List<String> numbers) {
    }
}
