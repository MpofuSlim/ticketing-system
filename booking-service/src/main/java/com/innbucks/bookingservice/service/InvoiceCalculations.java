package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

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
