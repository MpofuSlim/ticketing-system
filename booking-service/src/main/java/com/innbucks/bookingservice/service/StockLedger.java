package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;

import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The one place a booking's tickets go back to stock, and the one definition
 * of the order stock rows are locked in.
 *
 * <p><b>The lock order.</b> Every path that changes stock takes the booking
 * row first (when it has one), then the {@code category_inventory} rows in
 * ascending {@code category_id}. Two transactions that each take a subsequence
 * of one global order cannot wait on each other in a cycle, so they cannot
 * deadlock. Before this, claims locked categories in the order the request
 * named them and releases grouped them in a {@code HashMap}, so a booking for
 * {@code [A,B]} racing one for {@code [B,A]} could deadlock, and Postgres'
 * deadlock abort reached the client as a 500.
 *
 * <p><b>"Ascending" means Postgres' order, not {@link UUID#compareTo}.</b>
 * Postgres compares {@code uuid} as 16 unsigned bytes; {@code UUID.compareTo}
 * compares two SIGNED longs, so the two disagree for every id whose first or
 * ninth byte has its top bit set. Any path that later locks these rows from
 * SQL ({@code ORDER BY category_id … FOR UPDATE}) must agree with the paths
 * that lock them one by one from Java, so the Java side sorts with
 * {@link #CATEGORY_ORDER}, which compares the same bytes the same way.
 *
 * <p>Not a Spring bean: each service builds one from the repository it
 * already holds, so the services' hand-built unit-test instances keep their
 * constructors. It holds no state.
 */
public final class StockLedger {

    /**
     * Postgres' {@code uuid} order: the 16 bytes, big-endian, unsigned. The
     * most significant long holds bytes 0-7, so comparing the two halves
     * unsigned is a byte-wise comparison.
     */
    public static final Comparator<UUID> CATEGORY_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private final CategoryInventoryRepository categoryInventoryRepository;

    public StockLedger(CategoryInventoryRepository categoryInventoryRepository) {
        this.categoryInventoryRepository = categoryInventoryRepository;
    }

    /** An empty map keyed in the canonical lock order. */
    public static <V> TreeMap<UUID, V> byCategory() {
        return new TreeMap<>(CATEGORY_ORDER);
    }

    /**
     * Return a cancelled, expired or reversed booking's tickets to their
     * category counters, one bulk {@code UPDATE} per category, in ascending
     * category order. The caller holds the booking row's lock (that is what
     * makes this run once per booking: each path moves the booking to
     * CANCELLED exactly once, under that lock) and runs this inside the same
     * transaction; reading {@code booking.getItems()} loads them there if they
     * are not loaded yet.
     */
    public void release(Booking booking) {
        List<BookingItem> items = booking.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        SortedMap<UUID, Integer> qtyByCategory = byCategory();
        for (BookingItem item : items) {
            qtyByCategory.merge(item.getCategoryId(), 1, Integer::sum);
        }
        qtyByCategory.forEach(categoryInventoryRepository::release);
    }
}
