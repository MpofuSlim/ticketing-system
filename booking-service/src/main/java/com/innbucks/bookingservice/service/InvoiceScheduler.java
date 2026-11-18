package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
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
 * tickets that sold in it — see {@link InvoiceService}. Each run bills every
 * period whose settle grace ended within {@code app.invoicing.catch-up-days}
 * ({@link InvoiceCalculations#billablePeriods}), so a period waiting out its
 * grace, or one whose ended events could not be resolved, is picked up on a
 * later run. It used to bill only the previous closed period, which a WEEKLY
 * period with the default 7-day grace no longer was by the time its grace
 * ended, so the scheduler never invoiced a weekly organizer.
 *
 * <p><b>Nothing is billed before {@code app.invoicing.billing-start-date}</b>,
 * and with it unset generation bills nothing at all (owner decision,
 * 2026-11-18: when commission is switched on, bill forward only). The overdue
 * and due-soon sweeps do not depend on it.
 */
@Component
@ConditionalOnProperty(prefix = "app.invoicing", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class InvoiceScheduler {

    private final InvoiceService invoiceService;
    private final InvoiceNotifier invoiceNotifier;
    private final BillingConfigService billingConfig;
    private final InvoiceProperties properties;
    private Clock clock = Clock.systemUTC();

    public InvoiceScheduler(InvoiceService invoiceService,
                            InvoiceNotifier invoiceNotifier,
                            BillingConfigService billingConfig,
                            InvoiceProperties properties) {
        this.invoiceService = invoiceService;
        this.invoiceNotifier = invoiceNotifier;
        this.billingConfig = billingConfig;
        this.properties = properties;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Say at startup whether generation will bill anything, so a missing start
     * date shows up in the boot log rather than as weeks without invoices.
     */
    @PostConstruct
    void reportBillingStart() {
        Optional<LocalDate> billingStart = billingStart();
        if (billingStart.isEmpty()) {
            log.error("Invoice generation is ON but will invoice NOTHING: {}", billingStartProblem());
            return;
        }
        log.info("Invoice generation invoices events that ended on or after {} ({}d settle grace, "
                        + "each period retried for {}d)", billingStart.get(), properties.getSettleGraceDays(),
                Math.max(1, properties.getCatchUpDays()));
    }

    /**
     * For each billing cycle, invoice every closed period due today, oldest
     * first ({@link InvoiceCalculations#billablePeriods}: its settle grace has
     * ended, within the catch-up window, not before the billing start date),
     * but only for organizers whose effective cycle matches, so a MONTHLY
     * organizer is invoiced in the monthly pass and skipped in the weekly one.
     *
     * <p>The grace is why a closed period waits: an event that ran on the
     * period's last day may still be refunding, and an invoice snapshots its
     * numbers, with no credit note to undo it. Runs daily; a period already
     * invoiced is skipped, and no day already on one of the organizer's
     * invoices is invoiced again ({@link InvoiceService#generateScheduled}).
     */
    @Scheduled(cron = "${app.invoicing.generation-cron:0 30 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "InvoiceScheduler.generateDue", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateDue() {
        Optional<LocalDate> billingStart = billingStart();
        if (billingStart.isEmpty()) {
            log.error("Invoice generation SKIPPED: {}", billingStartProblem());
            return;
        }
        LocalDate today = LocalDate.now(clock);
        // Each organizer's cycle is read once per run, so an organizer is billed
        // under one cycle per run even if an admin changes it meanwhile.
        Map<UUID, BillingCycle> cycleOf = new HashMap<>();
        for (BillingCycle cycle : BillingCycle.values()) {
            List<BillingPeriod> periods = InvoiceCalculations.billablePeriods(cycle, today,
                    properties.getSettleGraceDays(), properties.getCatchUpDays(), billingStart.get());
            if (periods.isEmpty()) {
                log.debug("Cycle={}: no period due on {} ({}d settle grace, billing from {})",
                        cycle, today, properties.getSettleGraceDays(), billingStart.get());
            }
            for (BillingPeriod period : periods) {
                generateForPeriod(cycle, period, cycleOf);
            }
        }
    }

    private void generateForPeriod(BillingCycle cycle, BillingPeriod period, Map<UUID, BillingCycle> cycleOf) {
        // Events that ENDED in the period, not tickets that sold in it.
        // Optional.empty() means event-service could not be asked — skip the
        // period rather than bill it with its events missing, which the
        // (organizer, period) idempotency key would then make permanent. Later
        // runs try it again while it is inside the catch-up window. See
        // InvoiceService.aggregateLinesForEndedEvents.
        Optional<Map<UUID, List<EventRevenueLine>>> resolved =
                invoiceService.aggregateLinesForEndedEvents(null, period.start(), period.endInclusive());
        if (resolved.isEmpty()) {
            log.warn("Cycle={} period={}..{}: could not resolve ended events — generation SKIPPED, "
                    + "will retry on the next run", cycle, period.start(), period.endInclusive());
            return;
        }
        Map<UUID, List<EventRevenueLine>> byOrganizer = resolved.get();

        int generated = 0;
        int onCycle = 0;
        for (Map.Entry<UUID, List<EventRevenueLine>> entry : byOrganizer.entrySet()) {
            UUID organizer = entry.getKey();
            BillingCycle organizerCycle =
                    cycleOf.computeIfAbsent(organizer, o -> billingConfig.resolve(o).getBillingCycle());
            if (organizerCycle != cycle) {
                continue; // billed under their own cycle's pass
            }
            onCycle++;
            if (invoiceService.generateScheduled(organizer, period, entry.getValue()).isPresent()) {
                generated++;
            }
        }
        log.info("Invoice generation cycle={} period={}..{}: {} organizer(s) with revenue, {} on this cycle, {} generated",
                cycle, period.start(), period.endInclusive(), byOrganizer.size(), onCycle, generated);
    }

    /** The parsed billing start date; empty when it is blank or not a date. */
    private Optional<LocalDate> billingStart() {
        String raw = properties.getBillingStartDate();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException notADate) {
            return Optional.empty();
        }
    }

    private String billingStartProblem() {
        String raw = properties.getBillingStartDate();
        String setting = "app.invoicing.billing-start-date (INVOICE_BILLING_START_DATE)";
        if (raw == null || raw.isBlank()) {
            return setting + " is not set. Set it to the first day commission is billed (yyyy-MM-dd); "
                    + "no event that ended before it is ever invoiced.";
        }
        return setting + " '" + raw.trim() + "' is not a date (yyyy-MM-dd).";
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
