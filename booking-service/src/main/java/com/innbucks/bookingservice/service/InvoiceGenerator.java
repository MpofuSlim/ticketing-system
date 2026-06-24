package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.EventInvoiceLineItem;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import com.innbucks.bookingservice.repository.EventInvoiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds and persists a single organizer's invoice for one period, in its own
 * transaction.
 *
 * <p>Deliberately a separate bean from {@link InvoiceService} so each call runs
 * in its own transaction: when the orchestrator loops over many organizers, one
 * organizer failing (or losing the unique-key race) rolls back only that
 * organizer's invoice, not the whole batch. (A {@code @Transactional} method
 * called from a sibling method of the same bean would not get its own
 * transaction — self-invocation bypasses the proxy.)
 *
 * <p>Idempotent: an organizer already invoiced for the exact period is skipped
 * via the cheap exists-check; the unique{@code (organizer, period)} constraint
 * is the backstop for a concurrent racer (surfaces as
 * {@code DataIntegrityViolationException}, handled by the caller).
 */
@Service
@Slf4j
public class InvoiceGenerator {

    private final EventInvoiceRepository invoices;
    private final BillingConfigService billingConfig;
    private final InvoiceProperties properties;

    public InvoiceGenerator(EventInvoiceRepository invoices,
                            BillingConfigService billingConfig,
                            InvoiceProperties properties) {
        this.invoices = invoices;
        this.billingConfig = billingConfig;
        this.properties = properties;
    }

    @Transactional
    public Optional<EventInvoice> generate(UUID organizerUuid, LocalDate periodStart, LocalDate periodEnd,
                                           List<EventRevenueLine> lines) {
        if (invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizerUuid, periodStart, periodEnd)) {
            log.debug("Invoice already exists organizer={} {}..{} — skipping", organizerUuid, periodStart, periodEnd);
            return Optional.empty();
        }

        OrganizerBillingConfig terms = billingConfig.resolve(organizerUuid);
        BigDecimal commissionRate = terms.getCommissionRate();
        BigDecimal vatRate = properties.getVatRate();

        long totalBookings = 0;
        long totalTickets = 0;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        List<EventInvoiceLineItem> items = new ArrayList<>(lines.size());

        for (EventRevenueLine line : lines) {
            BigDecimal gross = InvoiceCalculations.scale2(line.grossSales());
            // Round commission per line, then sum — so the line items add up to the
            // header commission to the penny (no rounding drift on the invoice).
            BigDecimal commission = InvoiceCalculations.percentage(gross, commissionRate);
            totalBookings += line.confirmedBookings();
            totalTickets += line.ticketsSold();
            totalGross = totalGross.add(gross);
            totalCommission = totalCommission.add(commission);
            items.add(EventInvoiceLineItem.builder()
                    .eventId(line.eventId())
                    .confirmedBookings(line.confirmedBookings())
                    .ticketsSold(line.ticketsSold())
                    .grossSales(gross)
                    .commissionAmount(commission)
                    .build());
        }

        // Nothing billable (no revenue, or a 0% rate) — don't issue a $0 invoice.
        if (totalCommission.signum() <= 0) {
            log.debug("No billable commission organizer={} {}..{} — skipping", organizerUuid, periodStart, periodEnd);
            return Optional.empty();
        }

        BigDecimal taxAmount = InvoiceCalculations.percentage(totalCommission, vatRate);
        BigDecimal totalAmount = totalCommission.add(taxAmount);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        EventInvoice invoice = EventInvoice.builder()
                .invoiceNumber(nextInvoiceNumber(periodEnd))
                .organizerUuid(organizerUuid)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .status(EventInvoice.InvoiceStatus.ISSUED)
                .currency(terms.getCurrency())
                .confirmedBookings(totalBookings)
                .ticketsSold(totalTickets)
                .grossSales(InvoiceCalculations.scale2(totalGross))
                .commissionRate(commissionRate)
                .commissionAmount(totalCommission)
                .taxRate(vatRate)
                .taxAmount(taxAmount)
                .totalAmount(totalAmount)
                .issuedAt(now)
                .dueAt(now.plusDays(properties.getDueDays()))
                .build();
        items.forEach(invoice::addLineItem);

        // saveAndFlush so a unique-key race surfaces here (as DataIntegrityViolationException)
        // rather than later at commit, where the caller couldn't attribute it to this organizer.
        EventInvoice saved = invoices.saveAndFlush(invoice);
        log.info("Generated invoice {} organizer={} {}..{} total={} {}",
                saved.getInvoiceNumber(), organizerUuid, periodStart, periodEnd,
                saved.getTotalAmount(), saved.getCurrency());
        return Optional.of(saved);
    }

    private String nextInvoiceNumber(LocalDate periodEnd) {
        long seq = invoices.nextInvoiceNumberValue();
        return String.format("INV-%d-%06d", periodEnd.getYear(), seq);
    }

    /** One (organizer, event) confirmed-revenue line feeding invoice generation. */
    public record EventRevenueLine(UUID eventId, long confirmedBookings, long ticketsSold, BigDecimal grossSales) {
    }
}
