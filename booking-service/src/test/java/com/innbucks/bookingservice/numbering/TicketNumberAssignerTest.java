package com.innbucks.bookingservice.numbering;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.NumberSeriesRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Assigned numbers (V34): who gets which number, in what order, and what is never written. */
class TicketNumberAssignerTest {

    private static final UUID TEN_K = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HALF = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PLAIN = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final NumberSeriesRepository repo = mock(NumberSeriesRepository.class);
    private final TicketNumberAssigner assigner = new TicketNumberAssigner(repo);

    private static BookingItem item(UUID category, int seat) {
        return BookingItem.builder().id(UUID.randomUUID()).categoryId(category).seatNumber(seat).build();
    }

    private static Booking booking(BookingItem... items) {
        Booking b = Booking.builder().id(UUID.randomUUID()).build();
        b.setItems(new ArrayList<>(List.of(items)));
        return b;
    }

    private static List<Object[]> block(long first, String label, String prefix, int padding) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{(int) first, label, prefix, (short) padding});
        return rows;
    }

    @Test
    void render_padsWithZeros_neverCuts_andTakesThePrefix() {
        assertEquals("T001", NumberFormat.render("T", 1, 3));
        assertEquals("T1000", NumberFormat.render("T", 1000, 3));
        assertEquals("7", NumberFormat.render("", 7, 0));
        assertEquals("007", NumberFormat.render(null, 7, 3));
        assertEquals("21K-0042", NumberFormat.render("21K-", 42, 4));
    }

    @Test
    void aConfirm_numbersEachNumberedCategory_inTheOrderTheBuyerListedTheTickets() {
        BookingItem third = item(TEN_K, 3);
        BookingItem first = item(TEN_K, 1);
        BookingItem halfMarathon = item(HALF, 2);
        BookingItem notNumbered = item(PLAIN, 4);
        Booking b = booking(third, notNumbered, first, halfMarathon);
        when(repo.findNumberedCategoryIds(anyCollection())).thenReturn(List.of(TEN_K, HALF));
        when(repo.claimBlock(eq(TEN_K), eq(2), any())).thenReturn(block(14, "Bib", "T", 3));
        when(repo.claimBlock(eq(HALF), eq(1), any())).thenReturn(block(1, "Bib", "", 0));
        when(repo.writeNumbers(anyString(), anyString(), anyString())).thenAnswer(call ->
                call.getArgument(0, String.class).split(",").length);

        assigner.assignOnConfirm(b);

        assertEquals("T014", first.getAssignedNumber());
        assertEquals("T015", third.getAssignedNumber());
        assertEquals("Bib", third.getNumberLabel());
        assertEquals("1", halfMarathon.getAssignedNumber());
        assertNull(notNumbered.getAssignedNumber());
        verify(repo).writeNumbers("{" + first.getId() + "," + third.getId() + "}", "{T014,T015}", "Bib");
        // Series rows are claimed in the global category order, so two confirms never wait on each other in a cycle.
        var order = inOrder(repo);
        order.verify(repo).claimBlock(eq(TEN_K), anyInt(), any());
        order.verify(repo).claimBlock(eq(HALF), anyInt(), any());
        verify(repo, never()).claimBlock(eq(PLAIN), anyInt(), any());
    }

    @Test
    void aBookingWithNoNumberedCategory_costsOneLookAndClaimsNothing() {
        when(repo.findNumberedCategoryIds(anyCollection())).thenReturn(List.of());

        assigner.assignOnConfirm(booking(item(PLAIN, 1)));

        verify(repo).findNumberedCategoryIds(anyCollection());
        verify(repo, never()).claimBlock(any(), anyInt(), any());
        verify(repo, never()).writeNumbers(anyString(), anyString(), anyString());
    }

    @Test
    void ticketsAlreadyNumbered_areNeverNumberedAgain_soAReplayCostsNothing() {
        BookingItem numbered = item(TEN_K, 1);
        numbered.setAssignedNumber("T001");

        assigner.assignOnConfirm(booking(numbered));

        verifyNoInteractions(repo);
        assertEquals("T001", numbered.getAssignedNumber());
    }

    @Test
    void aWriteThatMissesATicket_failsTheTransaction_ratherThanLoseOrRepeatANumber() {
        when(repo.findNumberedCategoryIds(anyCollection())).thenReturn(List.of(TEN_K));
        when(repo.claimBlock(eq(TEN_K), eq(2), any())).thenReturn(block(1, "Bib", "T", 3));
        when(repo.writeNumbers(anyString(), anyString(), anyString())).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> assigner.assignOnConfirm(booking(item(TEN_K, 1), item(TEN_K, 2))));
    }

    @Test
    void assignSold_locksTheSeriesFirst_continuesIt_andWritesInBatches() {
        List<UUID> ids = new ArrayList<>();
        for (int k = 0; k < TicketNumberAssigner.WRITE_BATCH + 1; k++) {
            ids.add(UUID.randomUUID());
        }
        when(repo.lockSeries(TEN_K)).thenReturn(List.of(TEN_K));
        when(repo.findUnnumberedPaidItemIds(TEN_K)).thenReturn(ids);
        when(repo.claimBlock(eq(TEN_K), eq(ids.size()), any())).thenReturn(block(38, "Bib", "T", 3));
        when(repo.writeNumbers(anyString(), anyString(), anyString())).thenAnswer(call ->
                call.getArgument(0, String.class).split(",").length);

        assertEquals(OptionalInt.of(ids.size()), assigner.assignSold(TEN_K));

        ArgumentCaptor<String> numbers = ArgumentCaptor.forClass(String.class);
        verify(repo, times(2)).writeNumbers(anyString(), numbers.capture(), eq("Bib"));
        assertTrue(numbers.getAllValues().get(0).startsWith("{T038,T039,"));
        assertEquals("{T538}", numbers.getAllValues().get(1));
        var order = inOrder(repo);
        order.verify(repo).lockSeries(TEN_K);
        order.verify(repo).findUnnumberedPaidItemIds(TEN_K);
        order.verify(repo).claimBlock(eq(TEN_K), anyInt(), any());
    }

    @Test
    void assignSold_withoutASeries_isEmpty_andWithNothingLeft_isZero() {
        when(repo.lockSeries(PLAIN)).thenReturn(List.of());
        assertTrue(assigner.assignSold(PLAIN).isEmpty());

        when(repo.lockSeries(TEN_K)).thenReturn(List.of(TEN_K));
        when(repo.findUnnumberedPaidItemIds(TEN_K)).thenReturn(List.of());
        assertEquals(OptionalInt.of(0), assigner.assignSold(TEN_K));
        verify(repo, never()).claimBlock(any(), anyInt(), any());
    }

    @Test
    void theArrayLiteral_refusesAnythingThatWouldNeedQuoting() {
        assertEquals("{T001,T002}", TicketNumberAssigner.arrayLiteral(List.of("T001", "T002")));
        assertThrows(IllegalArgumentException.class, () -> TicketNumberAssigner.arrayLiteral(List.of("T0,01")));
        assertThrows(IllegalArgumentException.class, () -> TicketNumberAssigner.arrayLiteral(List.of("x\"}")));
    }
}
