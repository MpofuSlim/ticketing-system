package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.config.InvoiceMetrics;
import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.dto.invoice.InvoiceSummaryResponse;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.EventInvoiceRepository;
import com.innbucks.bookingservice.repository.InvoiceAggregationRepository;
import com.innbucks.bookingservice.repository.projection.InvoiceStatusAggregate;
import com.innbucks.bookingservice.repository.projection.OrganizerEventRevenueRow;
import com.innbucks.bookingservice.repository.projection.OrganizerEventTicketRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceServiceTest {

    private InvoiceAggregationRepository aggregation;
    private EventInvoiceRepository invoices;
    private InvoiceGenerator generator;
    private InvoiceMetrics metrics;
    private EventServiceClient eventServiceClient;
    private InvoiceService service;

    private final UUID organizer = UUID.randomUUID();
    private final LocalDate start = LocalDate.of(2026, 5, 1);
    private final LocalDate end = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        aggregation = mock(InvoiceAggregationRepository.class);
        invoices = mock(EventInvoiceRepository.class);
        generator = mock(InvoiceGenerator.class);
        metrics = mock(InvoiceMetrics.class);
        eventServiceClient = mock(EventServiceClient.class);
        service = new InvoiceService(aggregation, invoices, generator, metrics,
                mock(InvoiceNotifier.class), eventServiceClient, "tok", "USD");
    }

    // -- Event-completion billing -------------------------------------------
    // An organizer is invoiced AFTER their event has run, so the period selects
    // on the EVENT's end date and the bookings are taken whenever they sold.

    private static ApiResult<List<UUID>> endedEvents(List<UUID> ids) {
        return ApiResult.<List<UUID>>builder().code("200").message("ok").data(ids).build();
    }

    @Test
    void endedEvents_billsEveryConfirmedBooking_whenEVERitSold() {
        UUID event = UUID.randomUUID();
        when(eventServiceClient.eventIdsEndedBetween(any(), any(), eq("tok")))
                .thenReturn(endedEvents(List.of(event)));
        when(aggregation.aggregateConfirmedRevenueForEvents(null, List.of(event)))
                .thenReturn(List.of(revenueRow(organizer, event, 3, "300.00")));
        when(aggregation.aggregateTicketCountsForEvents(null, List.of(event)))
                .thenReturn(List.of(ticketRow(organizer, event, 5)));

        var lines = service.aggregateLinesForEndedEvents(null, start, end);

        assertThat(lines).isPresent();
        assertThat(lines.get().get(organizer)).singleElement()
                .satisfies(l -> {
                    assertThat(l.eventId()).isEqualTo(event);
                    assertThat(l.grossSales()).isEqualByComparingTo("300.00");
                    assertThat(l.ticketsSold()).isEqualTo(5);
                });
        // No sale-date window is passed: a ticket bought months before the event
        // still belongs to the event's invoice. Passing one is the bug this replaced.
        verify(aggregation, never()).aggregateConfirmedRevenue(any(), any(), any());
    }

    /**
     * The distinction that bills money. An empty LIST means "asked, nothing
     * ended" and is allowed to produce (no) invoices; a NULL payload means
     * event-service could not be asked, and must abort the whole period.
     * Collapsing them would let one failed S2S call issue a period's invoices
     * with its completed events missing — and the (organizer, period) unique key
     * makes that under-billing permanent, since a later run finds the period
     * already invoiced.
     */
    @Test
    void endedEvents_returnsEmptyOptional_whenEventServiceCannotBeAsked() {
        when(eventServiceClient.eventIdsEndedBetween(any(), any(), eq("tok")))
                .thenReturn(ApiResult.<List<UUID>>builder().code("503").message("down").data(null).build());

        assertThat(service.aggregateLinesForEndedEvents(null, start, end))
                .as("a period whose ended events are unknown must be skipped, never billed as empty")
                .isEmpty();

        verify(aggregation, never()).aggregateConfirmedRevenueForEvents(any(), any());
    }

    @Test
    void endedEvents_returnsEmptyOptional_whenTheCallThrows() {
        when(eventServiceClient.eventIdsEndedBetween(any(), any(), eq("tok")))
                .thenThrow(new RuntimeException("connect timed out"));

        assertThat(service.aggregateLinesForEndedEvents(null, start, end)).isEmpty();
        verify(aggregation, never()).aggregateConfirmedRevenueForEvents(any(), any());
    }

    /**
     * No events ended is a real, billable answer meaning "nobody owes anything"
     * — distinct from the failures above. It must NOT reach the aggregation
     * query either, because `IN ()` is invalid SQL.
     */
    @Test
    void endedEvents_noEventsEnded_isAnEmptyMapNotAFailure_andSkipsTheQuery() {
        when(eventServiceClient.eventIdsEndedBetween(any(), any(), eq("tok")))
                .thenReturn(endedEvents(List.of()));

        var lines = service.aggregateLinesForEndedEvents(null, start, end);

        assertThat(lines).isPresent();
        assertThat(lines.get()).isEmpty();
        verify(aggregation, never()).aggregateConfirmedRevenueForEvents(any(), any());
    }

    @Test
    void markPaid_setsStatusAndTimestamp() {
        EventInvoice inv = issued(UUID.randomUUID());
        when(invoices.findById(inv.getId())).thenReturn(Optional.of(inv));
        when(invoices.save(any(EventInvoice.class))).thenAnswer(i -> i.getArgument(0));

        InvoiceResponse resp = service.markPaid(inv.getId());

        assertThat(resp.status()).isEqualTo("PAID");
        assertThat(inv.getPaidAt()).isNotNull();
        verify(metrics).incPaid();
    }

    @Test
    void markPaid_isIdempotentOnAlreadyPaid() {
        EventInvoice inv = issued(UUID.randomUUID());
        inv.setStatus(InvoiceStatus.PAID);
        when(invoices.findById(inv.getId())).thenReturn(Optional.of(inv));

        InvoiceResponse resp = service.markPaid(inv.getId());

        assertThat(resp.status()).isEqualTo("PAID");
        verify(invoices, never()).save(any());
        verify(metrics, never()).incPaid();
    }

    @Test
    void markPaid_rejectsCancelledInvoice() {
        EventInvoice inv = issued(UUID.randomUUID());
        inv.setStatus(InvoiceStatus.CANCELLED);
        when(invoices.findById(inv.getId())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.markPaid(inv.getId()))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void cancel_rejectsPaidInvoice() {
        EventInvoice inv = issued(UUID.randomUUID());
        inv.setStatus(InvoiceStatus.PAID);
        when(invoices.findById(inv.getId())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.cancel(inv.getId()))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("paid");
    }

    @Test
    void getById_withOrganizerScope_missIs404_andDoesNotLeak() {
        UUID id = UUID.randomUUID();
        when(invoices.findByIdAndOrganizerUuid(id, organizer)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id, organizer))
                .isInstanceOf(NotFoundException.class);
        // Scoped lookup used — never the unscoped findById.
        verify(invoices, never()).findById(id);
    }

    @Test
    void summary_splitsOutstandingFromPaid() {
        // Build the mocks BEFORE the outer when() — stubbing them inside the
        // thenReturn(...) argument would nest stubbing and trip UnfinishedStubbing.
        List<InvoiceStatusAggregate> rows = List.of(
                aggregate(InvoiceStatus.ISSUED, 2, "200.00"),
                aggregate(InvoiceStatus.OVERDUE, 1, "100.00"),
                aggregate(InvoiceStatus.PAID, 5, "500.00"),
                aggregate(InvoiceStatus.CANCELLED, 1, "50.00"));
        when(invoices.summariseByStatus()).thenReturn(rows);

        InvoiceSummaryResponse s = service.summary();

        assertThat(s.totalInvoices()).isEqualTo(9);
        assertThat(s.issuedCount()).isEqualTo(2);
        assertThat(s.overdueCount()).isEqualTo(1);
        assertThat(s.paidCount()).isEqualTo(5);
        assertThat(s.cancelledCount()).isEqualTo(1);
        assertThat(s.outstandingAmount()).isEqualByComparingTo("300.00"); // issued + overdue
        assertThat(s.paidAmount()).isEqualByComparingTo("500.00");
        assertThat(s.totalBilled()).isEqualByComparingTo("850.00");
    }

    @Test
    void generateForPeriod_aggregatesAndDelegatesPerOrganizer() {
        UUID event = UUID.randomUUID();
        // Build projection mocks before the when() to avoid nested stubbing.
        OrganizerEventRevenueRow revenue = revenueRow(organizer, event, 2, "150.00");
        OrganizerEventTicketRow tickets = ticketRow(organizer, event, 3);
        EventInvoice generatedInvoice = issued(UUID.randomUUID());
        when(aggregation.aggregateConfirmedRevenue(eq(null), any(), any())).thenReturn(List.of(revenue));
        when(aggregation.aggregateTicketCounts(eq(null), any(), any())).thenReturn(List.of(tickets));
        when(generator.generate(eq(organizer), eq(start), eq(end), any()))
                .thenReturn(Optional.of(generatedInvoice));

        List<InvoiceResponse> result = service.generateForPeriod(null, start, end);

        assertThat(result).hasSize(1);
        verify(metrics).incGenerated(any());
    }

    private EventInvoice issued(UUID id) {
        return EventInvoice.builder()
                .id(id)
                .invoiceNumber("INV-2026-000001")
                .organizerUuid(organizer)
                .periodStart(start).periodEnd(end)
                .status(InvoiceStatus.ISSUED)
                .currency("USD")
                .confirmedBookings(1).ticketsSold(1)
                .grossSales(new BigDecimal("100.00"))
                .commissionRate(new BigDecimal("10.0"))
                .commissionAmount(new BigDecimal("10.00"))
                .taxRate(new BigDecimal("15.0"))
                .taxAmount(new BigDecimal("1.50"))
                .totalAmount(new BigDecimal("11.50"))
                .issuedAt(LocalDateTime.of(2026, 6, 1, 1, 30))
                .dueAt(LocalDateTime.of(2026, 6, 15, 1, 30))
                .build();
    }

    // Projections are interfaces — implement them as plain objects rather than
    // Mockito mocks, so building several inside a List doesn't nest stubbing.
    private static InvoiceStatusAggregate aggregate(InvoiceStatus status, long count, String total) {
        BigDecimal totalAmount = new BigDecimal(total);
        return new InvoiceStatusAggregate() {
            public InvoiceStatus getStatus() {
                return status;
            }

            public long getCount() {
                return count;
            }

            public BigDecimal getTotal() {
                return totalAmount;
            }
        };
    }

    private static OrganizerEventRevenueRow revenueRow(UUID org, UUID event, long bookings, String gross) {
        BigDecimal grossSales = new BigDecimal(gross);
        return new OrganizerEventRevenueRow() {
            public UUID getOrganizerUuid() {
                return org;
            }

            public UUID getEventId() {
                return event;
            }

            public long getConfirmedBookings() {
                return bookings;
            }

            public BigDecimal getGrossSales() {
                return grossSales;
            }
        };
    }

    private static OrganizerEventTicketRow ticketRow(UUID org, UUID event, long tickets) {
        return new OrganizerEventTicketRow() {
            public UUID getOrganizerUuid() {
                return org;
            }

            public UUID getEventId() {
                return event;
            }

            public long getTicketsSold() {
                return tickets;
            }
        };
    }
}
