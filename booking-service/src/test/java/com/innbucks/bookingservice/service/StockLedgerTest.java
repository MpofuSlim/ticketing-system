package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StockLedgerTest {

    // Postgres sorts uuid by its bytes, unsigned: 7f… < 80… < ff…. Java's
    // UUID.compareTo compares SIGNED longs and puts 80… and ff… BEFORE 7f….
    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MID = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000000");
    private static final UUID TOP = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID LOW_HALF_HIGH = UUID.fromString("00000000-0000-0000-8000-000000000000");
    private static final UUID LOW_HALF_LOW = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");

    @Test
    void categoryOrder_isPostgresUuidOrder_notUuidCompareTo() {
        List<UUID> ids = new ArrayList<>(List.of(TOP, HIGH, LOW_HALF_HIGH, MID, LOW, LOW_HALF_LOW));

        ids.sort(StockLedger.CATEGORY_ORDER);

        // What `ORDER BY category_id` returns in Postgres.
        assertThat(ids).containsExactly(LOW, LOW_HALF_LOW, LOW_HALF_HIGH, MID, HIGH, TOP);
        // And the trap it avoids: Java's own order disagrees.
        assertThat(HIGH.compareTo(MID)).isNegative();
        assertThat(StockLedger.CATEGORY_ORDER.compare(HIGH, MID)).isPositive();
    }

    @Test
    void release_groupsByCategory_andReleasesInAscendingOrder_oneStatementPerCategory() {
        CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
        Booking booking = bookingWith(TOP, MID, HIGH, TOP, LOW, MID, TOP);

        new StockLedger(inventory).release(booking);

        InOrder order = inOrder(inventory);
        order.verify(inventory).release(LOW, 1);
        order.verify(inventory).release(MID, 2);
        order.verify(inventory).release(HIGH, 1);
        order.verify(inventory).release(TOP, 3);
        order.verifyNoMoreInteractions();
    }

    @Test
    void release_ofABookingWithNoItems_touchesNothing() {
        CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);

        new StockLedger(inventory).release(Booking.builder().build());
        new StockLedger(inventory).release(Booking.builder().items(new ArrayList<>()).build());

        verifyNoInteractions(inventory);
    }

    @Test
    void byCategory_isKeyedInTheSameOrder() {
        var map = StockLedger.<Integer>byCategory();
        map.put(TOP, 1);
        map.put(LOW, 1);
        map.put(HIGH, 1);
        map.put(MID, 1);
        assertThat(map.keySet()).containsExactly(LOW, MID, HIGH, TOP);
    }

    private static Booking bookingWith(UUID... categories) {
        Booking booking = Booking.builder().id(UUID.randomUUID()).items(new ArrayList<>()).build();
        for (UUID category : categories) {
            booking.getItems().add(BookingItem.builder().booking(booking).categoryId(category).build());
        }
        return booking;
    }
}
