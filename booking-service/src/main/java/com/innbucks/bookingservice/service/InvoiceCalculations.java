package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Pure invoicing arithmetic + billing-period math — no Spring, no DB, so it can
 * be unit-tested directly. Money is rounded to 2 dp HALF_UP at every step; rates
 * are percentages (10.0 = 10%).
 */
public final class InvoiceCalculations {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private InvoiceCalculations() {
    }

    /** {@code amount * ratePct / 100}, rounded to 2 dp HALF_UP. Null operands count as zero. */
    public static BigDecimal percentage(BigDecimal amount, BigDecimal ratePct) {
        if (amount == null || ratePct == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(ratePct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Null-safe scale to 2 dp HALF_UP. */
    public static BigDecimal scale2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The most recently <em>closed</em> billing period as of {@code asOf}, for the
     * given cycle. MONTHLY = the previous calendar month; WEEKLY = the previous
     * ISO week (Monday..Sunday). Both endpoints are inclusive days.
     */
    public static BillingPeriod previousClosed(BillingCycle cycle, LocalDate asOf) {
        if (cycle == BillingCycle.WEEKLY) {
            LocalDate thisMonday = asOf.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate start = thisMonday.minusWeeks(1);
            return new BillingPeriod(start, thisMonday.minusDays(1));
        }
        LocalDate firstOfThisMonth = asOf.withDayOfMonth(1);
        LocalDate start = firstOfThisMonth.minusMonths(1);
        return new BillingPeriod(start, firstOfThisMonth.minusDays(1));
    }

    /**
     * The first day a closed period may be invoiced: its last day, plus the
     * settle grace, plus one.
     *
     * <p>The {@code + 1} is the period itself — a period whose last day is
     * 31 Aug only actually closes at midnight on 1 Sept, so a zero grace means
     * "billable from 1 Sept", not "from 31 Aug". Getting that wrong bills a
     * period while its final day is still selling.
     *
     * <p>The grace on top exists because organizers are billed AFTER an event
     * runs, and an event on the period's last day may still be refunding. An
     * invoice snapshots its numbers and there is no credit note, so billing
     * before refunds settle leaves the organizer charged commission on a ticket
     * that no longer exists. A grace of 0 is legal and reinstates that race.
     *
     * <p>Pure so the arithmetic is testable without a clock — the scheduler
     * compares today against this.
     */
    public static LocalDate billableFrom(BillingPeriod period, int settleGraceDays) {
        return period.endInclusive().plusDays(Math.max(0, settleGraceDays) + 1L);
    }

    /**
     * The periods of {@code cycle} the scheduler bills on {@code today}, oldest
     * first: every closed period whose settle grace ended within the last
     * {@code catchUpDays} days (its {@link #billableFrom} is on or before today,
     * and no more than {@code catchUpDays - 1} days before it), that ended on or
     * after {@code billingStart}. A period that began before {@code billingStart}
     * is cut to start on it.
     *
     * <p><b>Why not just {@link #previousClosed}.</b> The previous closed period
     * is billable only once its grace has passed, and with a grace of a cycle
     * or more that never happens while it is still the previous one. WEEKLY with
     * the default 7-day grace: last week becomes billable on Monday next week,
     * by which time it is no longer last week, so no weekly period was ever
     * billed. Walking back to the periods whose grace has passed works for any
     * grace, and keeping each one for {@code catchUpDays} gives a period that
     * could not be billed on its first day (event-service unreachable, a missed
     * run) that many more daily tries.
     *
     * <p>With the default 28 days a WEEKLY run holds the last four billable
     * weeks, and a MONTHLY run never more than one month (months are 28 days or
     * longer), so MONTHLY bills the previous month from the 8th exactly as
     * before, and keeps trying it until a few days into the next month instead
     * of stopping at the month end.
     *
     * <p>{@code billingStart} is the floor below which nothing is billed
     * (owner decision, 2026-11-18: when commission is switched on, bill forward
     * only). Without it, the first run after the scheduler is switched on would
     * bill every period still in the catch-up window, at whatever rate applies
     * on that day, for weeks in which commission was off.
     */
    public static List<BillingPeriod> billablePeriods(BillingCycle cycle, LocalDate today, int settleGraceDays,
                                                      int catchUpDays, LocalDate billingStart) {
        LocalDate oldestBillableFrom = today.minusDays(Math.max(1, catchUpDays) - 1L);
        BillingPeriod period = previousClosed(cycle, today);
        while (billableFrom(period, settleGraceDays).isAfter(today)) {
            period = periodBefore(cycle, period);
        }
        Deque<BillingPeriod> billable = new ArrayDeque<>();
        while (!billableFrom(period, settleGraceDays).isBefore(oldestBillableFrom)
                && !period.endInclusive().isBefore(billingStart)) {
            billable.addFirst(period.start().isBefore(billingStart)
                    ? new BillingPeriod(billingStart, period.endInclusive())
                    : period);
            period = periodBefore(cycle, period);
        }
        return List.copyOf(billable);
    }

    /** The whole period of {@code cycle} just before {@code period}, which must be a whole period itself. */
    private static BillingPeriod periodBefore(BillingCycle cycle, BillingPeriod period) {
        LocalDate start = cycle == BillingCycle.WEEKLY
                ? period.start().minusWeeks(1)
                : period.start().minusMonths(1);
        return new BillingPeriod(start, period.start().minusDays(1));
    }

    /**
     * A billing period as inclusive [start, endInclusive] days, with helpers to
     * turn it into the half-open [start, endExclusive) instant range the
     * aggregation queries use (so the whole last day is counted).
     */
    public record BillingPeriod(LocalDate start, LocalDate endInclusive) {
        public LocalDateTime startDateTime() {
            return start.atStartOfDay();
        }

        public LocalDateTime endExclusiveDateTime() {
            return endInclusive.plusDays(1).atStartOfDay();
        }
    }
}
