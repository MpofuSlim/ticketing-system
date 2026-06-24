package com.innbucks.bookingservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Micrometer counters for the invoicing subsystem, exported at
 * {@code /actuator/prometheus} under the {@code booking.invoicing.*} prefix.
 * Mirrors the shape of {@code LoyaltyMetrics} so the dashboards/alerts read
 * consistently across services.
 */
@Component
public class InvoiceMetrics {

    private final Counter generated;
    private final Counter billedAmount;
    private final Counter generationSkipped;
    private final Counter paid;
    private final Counter cancelled;
    private final Counter overdueFlagged;
    private final Counter schedulerErrors;

    public InvoiceMetrics(MeterRegistry registry) {
        this.generated = Counter.builder("booking.invoicing.generated")
                .description("Event-organizer invoices generated")
                .baseUnit("invoices")
                .register(registry);
        this.billedAmount = Counter.builder("booking.invoicing.billed_amount")
                .description("Sum of invoiced totals (commission + tax)")
                .register(registry);
        this.generationSkipped = Counter.builder("booking.invoicing.generation_skipped")
                .description("Invoice generations skipped (zero billable revenue or already generated)")
                .baseUnit("invoices")
                .register(registry);
        this.paid = Counter.builder("booking.invoicing.paid")
                .description("Invoices marked paid")
                .baseUnit("invoices")
                .register(registry);
        this.cancelled = Counter.builder("booking.invoicing.cancelled")
                .description("Invoices cancelled")
                .baseUnit("invoices")
                .register(registry);
        this.overdueFlagged = Counter.builder("booking.invoicing.overdue_flagged")
                .description("Invoices flipped ISSUED->OVERDUE by the sweep")
                .baseUnit("invoices")
                .register(registry);
        this.schedulerErrors = Counter.builder("booking.invoicing.scheduler_errors")
                .description("Errors raised while generating invoices on the schedule")
                .register(registry);
    }

    public void incGenerated(BigDecimal totalBilled) {
        generated.increment();
        if (totalBilled != null && totalBilled.signum() > 0) {
            billedAmount.increment(totalBilled.doubleValue());
        }
    }

    public void incGenerationSkipped() {
        generationSkipped.increment();
    }

    public void incPaid() {
        paid.increment();
    }

    public void incCancelled() {
        cancelled.increment();
    }

    public void incOverdueFlagged(long count) {
        if (count > 0) {
            overdueFlagged.increment(count);
        }
    }

    public void incSchedulerError() {
        schedulerErrors.increment();
    }
}
