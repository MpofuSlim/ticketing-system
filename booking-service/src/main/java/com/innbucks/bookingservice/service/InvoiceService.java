package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceMetrics;
import com.innbucks.bookingservice.dto.invoice.InvoiceLineItemResponse;
import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.dto.invoice.InvoiceSummaryResponse;
import com.innbucks.bookingservice.dto.invoice.PageResponse;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.BookingConflictException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.EventInvoiceRepository;
import com.innbucks.bookingservice.repository.InvoiceAggregationRepository;
import com.innbucks.bookingservice.repository.projection.InvoiceStatusAggregate;
import com.innbucks.bookingservice.repository.projection.OrganizerEventRevenueRow;
import com.innbucks.bookingservice.repository.projection.OrganizerEventTicketRow;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates event-organizer invoicing: aggregates confirmed ticket revenue
 * into per-organizer line sets, drives idempotent generation (via
 * {@link InvoiceGenerator}, one transaction per organizer), and serves the
 * read + lifecycle (mark-paid / cancel / overdue-sweep) operations.
 *
 * <p>Revenue recognition matches {@link OrganizerReportService}: only CONFIRMED
 * bookings count, keyed on {@code createdAt} in UTC. A booking confirmed then
 * later reversed (refunded) is already excluded from the CONFIRMED aggregate, so
 * commission is charged on revenue currently kept. (A refund that lands in a
 * <em>later</em> period than the invoice is not retro-applied — that's a credit
 * note, deferred.)
 */
@Service
@Slf4j
public class InvoiceService {

    /** Guards an explicit admin period against an absurd unbounded span. */
    private static final int MAX_PERIOD_DAYS = 366;

    private final InvoiceAggregationRepository aggregation;
    private final EventInvoiceRepository invoices;
    private final InvoiceGenerator generator;
    private final InvoiceMetrics metrics;
    private final InvoiceNotifier invoiceNotifier;
    private final String cellCurrency;

    public InvoiceService(InvoiceAggregationRepository aggregation,
                          EventInvoiceRepository invoices,
                          InvoiceGenerator generator,
                          InvoiceMetrics metrics,
                          InvoiceNotifier invoiceNotifier,
                          @Value("${innbucks.currency:USD}") String cellCurrency) {
        this.aggregation = aggregation;
        this.invoices = invoices;
        this.generator = generator;
        this.metrics = metrics;
        this.invoiceNotifier = invoiceNotifier;
        this.cellCurrency = (cellCurrency == null || cellCurrency.isBlank()) ? "USD" : cellCurrency.trim();
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /**
     * Generate invoices for an explicit period (admin on-demand). {@code organizerFilter}
     * null spans every organizer with billable revenue in the window; a value scopes to
     * one. Idempotent + per-organizer isolated.
     */
    public List<InvoiceResponse> generateForPeriod(UUID organizerFilter, LocalDate periodStart, LocalDate periodEnd) {
        validatePeriod(periodStart, periodEnd);
        Map<UUID, List<EventRevenueLine>> byOrganizer = aggregateLines(organizerFilter, periodStart, periodEnd);
        List<InvoiceResponse> generated = new ArrayList<>();
        for (Map.Entry<UUID, List<EventRevenueLine>> entry : byOrganizer.entrySet()) {
            generateOne(entry.getKey(), periodStart, periodEnd, entry.getValue())
                    .ifPresent(inv -> generated.add(toResponse(inv, true)));
        }
        log.info("Generated {} invoice(s) for period {}..{} (organizerFilter={})",
                generated.size(), periodStart, periodEnd, organizerFilter);
        return generated;
    }

    /**
     * Aggregate CONFIRMED revenue in [periodStart, periodEnd] into per-organizer,
     * per-event lines. Two GROUP BYs (money + ticket count) merged by (organizer,
     * event) — see {@link InvoiceAggregationRepository} for why they're separate.
     */
    public Map<UUID, List<EventRevenueLine>> aggregateLines(UUID organizerFilter,
                                                            LocalDate periodStart, LocalDate periodEnd) {
        LocalDateTime start = periodStart.atStartOfDay();
        LocalDateTime end = periodEnd.plusDays(1).atStartOfDay();

        List<OrganizerEventRevenueRow> revenueRows = aggregation.aggregateConfirmedRevenue(organizerFilter, start, end);
        List<OrganizerEventTicketRow> ticketRows = aggregation.aggregateTicketCounts(organizerFilter, start, end);

        Map<String, Long> ticketsByKey = new LinkedHashMap<>();
        for (OrganizerEventTicketRow t : ticketRows) {
            ticketsByKey.put(key(t.getOrganizerUuid(), t.getEventId()), t.getTicketsSold());
        }

        Map<UUID, List<EventRevenueLine>> byOrganizer = new LinkedHashMap<>();
        for (OrganizerEventRevenueRow r : revenueRows) {
            long tickets = ticketsByKey.getOrDefault(key(r.getOrganizerUuid(), r.getEventId()), 0L);
            byOrganizer.computeIfAbsent(r.getOrganizerUuid(), k -> new ArrayList<>())
                    .add(new EventRevenueLine(r.getEventId(), r.getConfirmedBookings(), tickets, r.getGrossSales()));
        }
        return byOrganizer;
    }

    /**
     * Generate one organizer's invoice with metrics + error isolation. Used by both
     * the admin path and the scheduler. A unique-key race (concurrent generator)
     * and any unexpected error are swallowed to a skip so a batch never aborts.
     */
    public Optional<EventInvoice> generateOne(UUID organizerUuid, LocalDate periodStart, LocalDate periodEnd,
                                              List<EventRevenueLine> lines) {
        try {
            Optional<EventInvoice> invoice = generator.generate(organizerUuid, periodStart, periodEnd, lines);
            if (invoice.isPresent()) {
                metrics.incGenerated(invoice.get().getTotalAmount());
                // Email the organizer their invoice — best-effort, never fails the generation.
                invoiceNotifier.notifyIssued(invoice.get());
            } else {
                metrics.incGenerationSkipped();
            }
            return invoice;
        } catch (DataIntegrityViolationException race) {
            log.info("Invoice already exists organizer={} {}..{} (lost generation race) — skipping",
                    organizerUuid, periodStart, periodEnd);
            metrics.incGenerationSkipped();
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.error("Failed generating invoice organizer={} {}..{}", organizerUuid, periodStart, periodEnd, ex);
            metrics.incSchedulerError();
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * List invoices. {@code organizerScope} null = admin (all organizers, optionally
     * narrowed by {@code organizerFilter}); non-null = that organizer's own only.
     */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> list(UUID organizerScope, UUID organizerFilter,
                                              InvoiceStatus status, Pageable pageable) {
        UUID effectiveOrganizer = organizerScope != null ? organizerScope : organizerFilter;
        return PageResponse.of(invoices.search(effectiveOrganizer, status, pageable),
                inv -> toResponse(inv, false));
    }

    /**
     * Fetch one invoice with its line items. {@code organizerScope} non-null limits
     * to that organizer's own — a miss (absent OR owned by someone else) is a 404,
     * so existence of another organizer's invoice never leaks.
     */
    @Transactional(readOnly = true)
    public InvoiceResponse getById(UUID id, UUID organizerScope) {
        EventInvoice invoice = (organizerScope == null
                ? invoices.findById(id)
                : invoices.findByIdAndOrganizerUuid(id, organizerScope))
                .orElseThrow(() -> new NotFoundException("Invoice " + id + " not found"));
        return toResponse(invoice, true);
    }

    @Transactional(readOnly = true)
    public InvoiceSummaryResponse summary() {
        long total = 0;
        BigDecimal totalBilled = BigDecimal.ZERO;
        long issued = 0;
        long overdue = 0;
        long paid = 0;
        long cancelled = 0;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal paidAmount = BigDecimal.ZERO;

        for (InvoiceStatusAggregate row : invoices.summariseByStatus()) {
            long count = row.getCount();
            BigDecimal amount = row.getTotal() == null ? BigDecimal.ZERO : row.getTotal();
            total += count;
            totalBilled = totalBilled.add(amount);
            switch (row.getStatus()) {
                case ISSUED -> {
                    issued = count;
                    outstanding = outstanding.add(amount);
                }
                case OVERDUE -> {
                    overdue = count;
                    outstanding = outstanding.add(amount);
                }
                case PAID -> {
                    paid = count;
                    paidAmount = paidAmount.add(amount);
                }
                case CANCELLED -> cancelled = count;
            }
        }
        return new InvoiceSummaryResponse(total, InvoiceCalculations.scale2(totalBilled),
                issued, overdue, paid, cancelled,
                InvoiceCalculations.scale2(outstanding), InvoiceCalculations.scale2(paidAmount), cellCurrency);
    }

    // ------------------------------------------------------------------
    // Lifecycle transitions
    // ------------------------------------------------------------------

    @Transactional
    public InvoiceResponse markPaid(UUID id) {
        EventInvoice invoice = load(id);
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            return toResponse(invoice, true); // idempotent
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BookingConflictException("Cannot mark a cancelled invoice as paid.");
        }
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        metrics.incPaid();
        log.info("Invoice {} marked PAID", invoice.getInvoiceNumber());
        return toResponse(invoices.save(invoice), true);
    }

    @Transactional
    public InvoiceResponse cancel(UUID id) {
        EventInvoice invoice = load(id);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return toResponse(invoice, true); // idempotent
        }
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BookingConflictException("Cannot cancel a paid invoice.");
        }
        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelledAt(LocalDateTime.now(ZoneOffset.UTC));
        metrics.incCancelled();
        log.info("Invoice {} CANCELLED", invoice.getInvoiceNumber());
        return toResponse(invoices.save(invoice), true);
    }

    /**
     * Flip every unpaid, past-due ISSUED invoice to OVERDUE. Returns the
     * flipped invoices so the scheduler can email each organizer their
     * overdue notice AFTER this transaction commits (a notification failure
     * must never roll back the status flip).
     */
    @Transactional
    public List<EventInvoice> flagOverdue(LocalDateTime asOf) {
        List<EventInvoice> due = invoices.findIssuedPastDue(asOf);
        if (due.isEmpty()) {
            return List.of();
        }
        for (EventInvoice invoice : due) {
            invoice.setStatus(InvoiceStatus.OVERDUE);
        }
        invoices.saveAll(due);
        metrics.incOverdueFlagged(due.size());
        log.info("Flagged {} invoice(s) OVERDUE", due.size());
        return due;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EventInvoice load(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("Invoice " + id + " not found"));
    }

    private void validatePeriod(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart == null || periodEnd == null) {
            throw new BadRequestException("periodStart and periodEnd are required.");
        }
        if (periodStart.isAfter(periodEnd)) {
            throw new BadRequestException(
                    "periodStart (" + periodStart + ") must not be after periodEnd (" + periodEnd + ").");
        }
        if (ChronoUnit.DAYS.between(periodStart, periodEnd) > MAX_PERIOD_DAYS) {
            throw new BadRequestException("Billing period must not exceed " + MAX_PERIOD_DAYS + " days.");
        }
    }

    private static String key(UUID organizerUuid, UUID eventId) {
        return organizerUuid + "|" + eventId;
    }

    /**
     * Map an invoice to its API shape. {@code includeLineItems=false} skips touching
     * the lazy {@code lineItems} collection — used by the list endpoint to avoid an
     * N+1 across the page.
     */
    private static InvoiceResponse toResponse(EventInvoice i, boolean includeLineItems) {
        List<InvoiceLineItemResponse> lines = includeLineItems
                ? i.getLineItems().stream()
                .map(li -> new InvoiceLineItemResponse(li.getEventId(), li.getConfirmedBookings(),
                        li.getTicketsSold(), li.getGrossSales(), li.getCommissionAmount()))
                .toList()
                : null;
        return new InvoiceResponse(
                i.getId(), i.getInvoiceNumber(), i.getOrganizerUuid(),
                i.getPeriodStart(), i.getPeriodEnd(), i.getStatus().name(), i.getCurrency(),
                i.getConfirmedBookings(), i.getTicketsSold(), i.getGrossSales(),
                i.getCommissionRate(), i.getCommissionAmount(), i.getTaxRate(), i.getTaxAmount(), i.getTotalAmount(),
                i.getIssuedAt(), i.getDueAt(), i.getPaidAt(), i.getCancelledAt(), lines);
    }
}
