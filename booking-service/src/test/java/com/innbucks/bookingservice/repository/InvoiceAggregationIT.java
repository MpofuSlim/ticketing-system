package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.Booking.BookingStatus;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.repository.projection.OrganizerEventRevenueRow;
import com.innbucks.bookingservice.repository.projection.OrganizerEventTicketRow;
import com.innbucks.bookingservice.service.InvoiceService;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof of the invoicing money path:
 * <ul>
 *   <li>confirmed-revenue aggregation SUMs {@code totalAmount} per booking and
 *       does NOT fan out by ticket count (the 100×-over-bill guard),</li>
 *   <li>PENDING / CANCELLED bookings are excluded,</li>
 *   <li>end-to-end generation produces the right commission + VAT + line items
 *       and is idempotent for the same (organizer, period).</li>
 * </ul>
 */
@TestPropertySource(properties = {
        "app.invoicing.default-commission-rate=10.0",
        "app.invoicing.vat-rate=15.0",
        "app.invoicing.scheduler-enabled=false"
})
class InvoiceAggregationIT extends PostgresIntegrationTestBase {

    @Autowired
    private BookingRepository bookings;
    @Autowired
    private InvoiceAggregationRepository aggregation;
    @Autowired
    private EventInvoiceRepository invoices;
    @Autowired
    private InvoiceService invoiceService;

    @Test
    void aggregatesConfirmedRevenueWithoutFanOut_andExcludesNonConfirmed() {
        UUID organizerA = UUID.randomUUID();
        UUID organizerB = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();

        // Organizer A / event 1: two CONFIRMED bookings ($100 over 2 tickets, $50 over 1).
        save(organizerA, e1, BookingStatus.CONFIRMED, "100.00", 2);
        save(organizerA, e1, BookingStatus.CONFIRMED, "50.00", 1);
        // Excluded: a PENDING hold and a CANCELLED booking on the same event.
        save(organizerA, e1, BookingStatus.PENDING, "999.00", 1);
        save(organizerA, e1, BookingStatus.CANCELLED, "888.00", 1);
        // Organizer A / event 2: one CONFIRMED booking ($300 over 3 tickets).
        save(organizerA, e2, BookingStatus.CONFIRMED, "300.00", 3);
        // Organizer B: one CONFIRMED booking (must not bleed into A's totals).
        save(organizerB, UUID.randomUUID(), BookingStatus.CONFIRMED, "70.00", 1);

        LocalDateTime start = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        LocalDateTime end = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay();

        Map<UUID, OrganizerEventRevenueRow> revenueByEvent = aggregation
                .aggregateConfirmedRevenue(organizerA, start, end).stream()
                .collect(Collectors.toMap(OrganizerEventRevenueRow::getEventId, Function.identity()));

        assertThat(revenueByEvent).containsOnlyKeys(e1, e2);
        // THE guard: e1 gross is 150.00 (100 + 50), NOT fanned out to 250 by the item join.
        assertThat(revenueByEvent.get(e1).getGrossSales()).isEqualByComparingTo("150.00");
        assertThat(revenueByEvent.get(e1).getConfirmedBookings()).isEqualTo(2);
        assertThat(revenueByEvent.get(e2).getGrossSales()).isEqualByComparingTo("300.00");
        assertThat(revenueByEvent.get(e2).getConfirmedBookings()).isEqualTo(1);

        Map<UUID, Long> ticketsByEvent = aggregation.aggregateTicketCounts(organizerA, start, end).stream()
                .collect(Collectors.toMap(OrganizerEventTicketRow::getEventId, OrganizerEventTicketRow::getTicketsSold));
        assertThat(ticketsByEvent.get(e1)).isEqualTo(3); // 2 + 1
        assertThat(ticketsByEvent.get(e2)).isEqualTo(3);

        // Organizer filter isolates B from A.
        assertThat(aggregation.aggregateConfirmedRevenue(organizerB, start, end)).hasSize(1);
    }

    @Test
    void generatesInvoiceWithCorrectMath_andIsIdempotent() {
        UUID organizer = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        save(organizer, e1, BookingStatus.CONFIRMED, "100.00", 2);
        save(organizer, e1, BookingStatus.CONFIRMED, "50.00", 1);
        save(organizer, e2, BookingStatus.CONFIRMED, "300.00", 3);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<InvoiceResponse> generated = invoiceService.generateForPeriod(organizer, today, today);

        assertThat(generated).hasSize(1);
        InvoiceResponse inv = generated.get(0);
        // gross 450; commission 10% = 45.00; VAT 15% of 45 = 6.75; total 51.75
        assertThat(inv.grossSales()).isEqualByComparingTo("450.00");
        assertThat(inv.commissionAmount()).isEqualByComparingTo("45.00");
        assertThat(inv.taxAmount()).isEqualByComparingTo("6.75");
        assertThat(inv.totalAmount()).isEqualByComparingTo("51.75");
        assertThat(inv.confirmedBookings()).isEqualTo(3);
        assertThat(inv.ticketsSold()).isEqualTo(6);
        assertThat(inv.lineItems()).hasSize(2);
        assertThat(inv.invoiceNumber()).startsWith("INV-" + today.getYear() + "-");

        // Persisted with its line items.
        EventInvoice persisted = invoices.findById(inv.id()).orElseThrow();
        assertThat(persisted.getLineItems()).hasSize(2);
        assertThat(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, today, today)).isTrue();

        // Re-running the same period is a no-op (idempotent).
        assertThat(invoiceService.generateForPeriod(organizer, today, today)).isEmpty();
    }

    private void save(UUID organizer, UUID eventId, BookingStatus status, String total, int ticketCount) {
        Booking booking = Booking.builder()
                .eventId(eventId)
                .tenantUserUuid(organizer)
                .userEmail("customer@example.com")
                .confirmationNumber("CONF-" + UUID.randomUUID())
                .status(status)
                .totalAmount(new BigDecimal(total))
                .availabilityReleased(false)
                .build();
        List<BookingItem> items = new ArrayList<>();
        for (int i = 0; i < ticketCount; i++) {
            items.add(BookingItem.builder()
                    .booking(booking)
                    .seatId(UUID.randomUUID())
                    .categoryId(UUID.randomUUID())
                    .rowLabel("A")
                    .seatNumber(i + 1)
                    .categoryName("GENERAL")
                    .priceAtBooking(new BigDecimal(total).divide(new BigDecimal(ticketCount), 2, java.math.RoundingMode.HALF_UP))
                    .ticketNumber("TIX-" + UUID.randomUUID())
                    .isActive(Boolean.TRUE)
                    .build());
        }
        booking.setItems(items);
        bookings.save(booking);
    }
}
