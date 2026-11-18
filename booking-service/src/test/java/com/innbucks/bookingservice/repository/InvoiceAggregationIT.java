package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.Booking.BookingStatus;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus;
import com.innbucks.bookingservice.repository.projection.OrganizerEventRevenueRow;
import com.innbucks.bookingservice.repository.projection.OrganizerEventTicketRow;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import com.innbucks.bookingservice.service.InvoiceService;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *       and is idempotent for the same (organizer, period),</li>
 *   <li>scheduled generation never invoices a day twice: the overlap query
 *       counts invoices of every status, and only the organizer's own.</li>
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
    @Autowired
    private PlatformTransactionManager txManager;

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

        // Persisted with its line items. Reload + read the lazy collection inside a
        // transaction so the session is still open when we navigate it.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            EventInvoice persisted = invoices.findById(inv.id()).orElseThrow();
            assertThat(persisted.getLineItems()).hasSize(2);
        });
        assertThat(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, today, today)).isTrue();

        // Re-running the same period is a no-op (idempotent).
        assertThat(invoiceService.generateForPeriod(organizer, today, today)).isEmpty();
    }

    // -- Scheduled generation never invoices a day twice ----------------------

    @Test
    void latestOverlappingPeriodEnd_readsEveryStatus_andOnlyThatOrganizersOverlappingInvoices() {
        UUID organizer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        saveInvoice(organizer, "2026-10-01", "2026-10-31", InvoiceStatus.PAID);
        saveInvoice(organizer, "2026-11-02", "2026-11-08", InvoiceStatus.CANCELLED);
        saveInvoice(other, "2026-11-01", "2026-11-30", InvoiceStatus.ISSUED);

        // A week straddling October's end, and one day touching it.
        assertThat(invoices.findLatestOverlappingPeriodEnd(organizer, day("2026-10-26"), day("2026-11-01")))
                .contains(day("2026-10-31"));
        assertThat(invoices.findLatestOverlappingPeriodEnd(organizer, day("2026-10-31"), day("2026-10-31")))
                .contains(day("2026-10-31"));
        // A CANCELLED invoice still covers its days: the scheduler must not re-issue it.
        assertThat(invoices.findLatestOverlappingPeriodEnd(organizer, day("2026-11-01"), day("2026-11-30")))
                .contains(day("2026-11-08"));
        // Gaps, and another organizer's invoices, cover nothing.
        assertThat(invoices.findLatestOverlappingPeriodEnd(organizer, day("2026-11-01"), day("2026-11-01"))).isEmpty();
        assertThat(invoices.findLatestOverlappingPeriodEnd(organizer, day("2026-11-09"), day("2026-11-15"))).isEmpty();
        assertThat(invoices.findLatestOverlappingPeriodEnd(other, day("2026-10-01"), day("2026-10-31"))).isEmpty();
    }

    @Test
    void scheduledGeneration_invoicesAWeekOnce_andNeverItsDaysAgain() {
        UUID organizer = UUID.randomUUID();
        List<EventRevenueLine> lines =
                List.of(new EventRevenueLine(UUID.randomUUID(), 1, 1, new BigDecimal("100.00")));
        BillingPeriod week = new BillingPeriod(day("2026-11-09"), day("2026-11-15"));

        assertThat(invoiceService.generateScheduled(organizer, week, lines)).isPresent();
        // Every later run inside the catch-up window finds the week invoiced ...
        assertThat(invoiceService.generateScheduled(organizer, week, lines)).isEmpty();
        // ... and so does a longer period ending on its last day, e.g. after a
        // cycle change (event-service is never asked: nothing is left to bill).
        assertThat(invoiceService.generateScheduled(organizer,
                new BillingPeriod(day("2026-11-01"), day("2026-11-15")), lines)).isEmpty();

        assertThat(invoices.findAll()).filteredOn(i -> i.getOrganizerUuid().equals(organizer))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.getPeriodStart()).isEqualTo(week.start());
                    assertThat(i.getPeriodEnd()).isEqualTo(week.endInclusive());
                });
    }

    private void saveInvoice(UUID organizer, String start, String end, InvoiceStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        invoices.saveAndFlush(EventInvoice.builder()
                .invoiceNumber("INV-IT-" + UUID.randomUUID().toString().replace("-", ""))
                .organizerUuid(organizer)
                .periodStart(day(start)).periodEnd(day(end))
                .status(status)
                .currency("USD")
                .grossSales(new BigDecimal("100.00"))
                .commissionRate(new BigDecimal("10.0"))
                .commissionAmount(new BigDecimal("10.00"))
                .taxRate(new BigDecimal("15.0"))
                .taxAmount(new BigDecimal("1.50"))
                .totalAmount(new BigDecimal("11.50"))
                .issuedAt(now)
                .dueAt(now.plusDays(14))
                .build());
    }

    private static LocalDate day(String isoDate) {
        return LocalDate.parse(isoDate);
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
