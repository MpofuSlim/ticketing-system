package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Periodic platform→organizer invoice generation and the overdue sweep.
 *
 * <p>ShedLock-guarded so only one pod runs each tick (the ShedLock infra is
 * already wired by {@code SchedulerLockConfig}). Generation is idempotent at the
 * data layer (unique {@code (organizer, period)}), so even without the lock a
 * double-run wouldn't double-bill — the lock just avoids the wasted work and
 * lock contention.
 *
 * <p>Disabled as a unit via {@code app.invoicing.scheduler-enabled=false} (e.g.
 * in tests). Crons default to the small hours UTC; the daily cadence is harmless
 * because generation only fires for a period that has closed, has cleared its
 * settle grace, and isn't already invoiced.
 *
 * <p><b>Generation bills the events that ENDED in the period</b>, not the
 * tickets that sold in it — see {@link InvoiceService}. The daily cadence is
 * what makes the two skip conditions safe: a period waiting out its grace, or
 * one whose ended events could not be resolved, is simply picked up on a later
 * run while it is still the previous closed period.
 */
@Component
@ConditionalOnProperty(prefix = "app.invoicing", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class InvoiceScheduler {

    private final InvoiceService invoiceService;
    private final InvoiceNotifier invoiceNotifier;
    private final BillingConfigService billingConfig;
    private final InvoiceProperties properties;

    public InvoiceScheduler(InvoiceService invoiceService,
                            InvoiceNotifier invoiceNotifier,
                            BillingConfigService billingConfig,
                            InvoiceProperties properties) {
        this.invoiceService = invoiceService;
        this.invoiceNotifier = invoiceNotifier;
        this.billingConfig = billingConfig;
        this.properties = properties;
    }

    /**
     * For each billing cycle, generate invoices for the most recently closed period
     * of that cycle, but only for organizers whose effective cycle matches — so a
     * MONTHLY organizer is invoiced in the monthly pass and skipped in the weekly
     * one. Runs daily; periods already invoiced are skipped idempotently.
     */
    @Scheduled(cron = "${app.invoicing.generation-cron:0 30 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "InvoiceScheduler.generateDue", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateDue() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (BillingCycle cycle : BillingCycle.values()) {
            BillingPeriod period = InvoiceCalculations.previousClosed(cycle, today);

            // Wait out the settle grace before billing a closed period. An event
            // that ran on the last day of the period may still be refunding, and
            // an invoice snapshots its numbers — bill too early and the organizer
            // is charged commission on a ticket that no longer exists, with no
            // credit note to undo it. Skipping is free: this runs daily and the
            // same period is still the previous closed one tomorrow.
            LocalDate billableFrom = InvoiceCalculations.billableFrom(period, properties.getSettleGraceDays());
            if (today.isBefore(billableFrom)) {
                log.debug("Cycle={} period={}..{} not billable until {} ({}d settle grace) — skipping",
                        cycle, period.start(), period.endInclusive(), billableFrom,
                        properties.getSettleGraceDays());
                continue;
            }

            // Events that ENDED in the period, not tickets that sold in it.
            // Optional.empty() means event-service could not be asked — skip the
            // whole cycle rather than bill a period with its events missing,
            // which the (organizer, period) idempotency key would then make
            // permanent. See InvoiceService.aggregateLinesForEndedEvents.
            Optional<Map<UUID, List<EventRevenueLine>>> resolved =
                    invoiceService.aggregateLinesForEndedEvents(null, period.start(), period.endInclusive());
            if (resolved.isEmpty()) {
                log.warn("Cycle={} period={}..{}: could not resolve ended events — generation SKIPPED, "
                        + "will retry on the next run", cycle, period.start(), period.endInclusive());
                continue;
            }
            Map<UUID, List<EventRevenueLine>> byOrganizer = resolved.get();

            int generated = 0;
            int onCycle = 0;
            for (Map.Entry<UUID, List<EventRevenueLine>> entry : byOrganizer.entrySet()) {
                UUID organizer = entry.getKey();
                if (billingConfig.resolve(organizer).getBillingCycle() != cycle) {
                    continue; // billed under their own cycle's pass
                }
                onCycle++;
                if (invoiceService.generateOne(organizer, period.start(), period.endInclusive(), entry.getValue())
                        .isPresent()) {
                    generated++;
                }
            }
            log.info("Invoice generation cycle={} period={}..{}: {} organizer(s) with revenue, {} on this cycle, {} generated",
                    cycle, period.start(), period.endInclusive(), byOrganizer.size(), onCycle, generated);
        }
    }

    /**
     * Flip unpaid, past-due ISSUED invoices to OVERDUE so the dashboard +
     * alerts see them, then email each organizer their overdue notice
     * (dunning). Notifications run after the flip has committed and are
     * best-effort per invoice — one failed email never blocks the rest.
     */
    @Scheduled(cron = "${app.invoicing.overdue-cron:0 0 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "InvoiceScheduler.sweepOverdue", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void sweepOverdue() {
        var flagged = invoiceService.flagOverdue(LocalDateTime.now(ZoneOffset.UTC));
        if (!flagged.isEmpty()) {
            log.info("Overdue sweep flagged {} invoice(s)", flagged.size());
            flagged.forEach(invoiceNotifier::notifyOverdue);
        }
    }

    /**
     * The nudge BEFORE the dunning: daily, email organizers whose ISSUED
     * invoices fall due within the lead window (default 3 days). The claim
     * (marker stamp) commits first; emails are best-effort per invoice after,
     * so one failed send never blocks the rest and is never retried.
     */
    @Scheduled(cron = "${app.invoicing.due-soon-cron:0 15 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "InvoiceScheduler.sweepDueSoon", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void sweepDueSoon() {
        var dueSoon = invoiceService.claimDueSoon(
                LocalDateTime.now(ZoneOffset.UTC), Duration.ofDays(dueSoonLeadDays));
        if (!dueSoon.isEmpty()) {
            log.info("Due-soon sweep claimed {} invoice(s)", dueSoon.size());
            dueSoon.forEach(invoiceNotifier::notifyDueSoon);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${app.invoicing.due-soon-lead-days:3}")
    private long dueSoonLeadDays;
}
