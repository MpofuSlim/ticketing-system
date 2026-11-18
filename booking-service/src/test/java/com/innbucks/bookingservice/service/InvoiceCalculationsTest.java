package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the money + period arithmetic that the rest of invoicing trusts. */
class InvoiceCalculationsTest {

    @Test
    void percentage_roundsToTwoDpHalfUp() {
        // 33.33% of 10.00 = 3.333 -> 3.33
        assertThat(InvoiceCalculations.percentage(new BigDecimal("10.00"), new BigDecimal("33.33")))
                .isEqualByComparingTo("3.33");
        // 15% of 45.05 = 6.7575 -> 6.76 (HALF_UP)
        assertThat(InvoiceCalculations.percentage(new BigDecimal("45.05"), new BigDecimal("15.0")))
                .isEqualByComparingTo("6.76");
        // 10% of 150 = 15.00
        assertThat(InvoiceCalculations.percentage(new BigDecimal("150"), new BigDecimal("10.0")))
                .isEqualByComparingTo("15.00");
    }

    @Test
    void percentage_isNullSafeAndScaleTwo() {
        assertThat(InvoiceCalculations.percentage(null, new BigDecimal("10"))).isEqualByComparingTo("0.00");
        assertThat(InvoiceCalculations.percentage(new BigDecimal("10"), null)).isEqualByComparingTo("0.00");
        assertThat(InvoiceCalculations.percentage(BigDecimal.ZERO, new BigDecimal("10")).scale()).isEqualTo(2);
    }

    @Test
    void scale2_normalisesNullAndScale() {
        assertThat(InvoiceCalculations.scale2(null)).isEqualByComparingTo("0.00");
        assertThat(InvoiceCalculations.scale2(new BigDecimal("12.5")).scale()).isEqualTo(2);
    }

    @Test
    void previousClosed_monthly_isThePreviousCalendarMonth() {
        // As of 12 Jun 2026, the last closed month is May 1..31.
        BillingPeriod p = InvoiceCalculations.previousClosed(BillingCycle.MONTHLY, LocalDate.of(2026, 6, 12));
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(p.endInclusive()).isEqualTo(LocalDate.of(2026, 5, 31));
        // Half-open instant range covers the whole last day.
        assertThat(p.endExclusiveDateTime()).isEqualTo(LocalDate.of(2026, 6, 1).atStartOfDay());
    }

    @Test
    void previousClosed_monthly_handlesYearAndFebruaryBoundary() {
        // As of 3 Jan 2026 -> previous month is Dec 2025.
        BillingPeriod dec = InvoiceCalculations.previousClosed(BillingCycle.MONTHLY, LocalDate.of(2026, 1, 3));
        assertThat(dec.start()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(dec.endInclusive()).isEqualTo(LocalDate.of(2025, 12, 31));
        // As of 5 Mar 2026 -> previous month is Feb 2026 (28 days).
        BillingPeriod feb = InvoiceCalculations.previousClosed(BillingCycle.MONTHLY, LocalDate.of(2026, 3, 5));
        assertThat(feb.endInclusive()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void previousClosed_weekly_isThePreviousMondayToSunday() {
        // 12 Jun 2026 is a Friday; previous ISO week is Mon 1 Jun .. Sun 7 Jun.
        BillingPeriod p = InvoiceCalculations.previousClosed(BillingCycle.WEEKLY, LocalDate.of(2026, 6, 12));
        assertThat(p.start().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(p.endInclusive()).isEqualTo(LocalDate.of(2026, 6, 7));
    }

    // -- Settle grace --------------------------------------------------------
    // Organizers are billed AFTER their event runs, and an event on the period's
    // last day may still be refunding. An invoice snapshots its numbers and
    // there is no credit note, so billing before refunds settle charges
    // commission on a ticket that no longer exists.

    @Test
    void billableFrom_waitsTheGraceAfterThePeriodActuallyCloses() {
        BillingPeriod august = new BillingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        // The period's last day is 31 Aug, so it only CLOSES at midnight on
        // 1 Sept — the grace runs from there, not from the 31st.
        assertThat(InvoiceCalculations.billableFrom(august, 7)).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    void billableFrom_zeroGrace_isTheDayAfterThePeriodEnds_notItsLastDay() {
        BillingPeriod august = new BillingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        // Off-by-one guard: billing on the 31st would invoice a period whose
        // final day is still selling tickets.
        assertThat(InvoiceCalculations.billableFrom(august, 0)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void billableFrom_treatsANegativeGraceAsZero_ratherThanBillingEarly() {
        BillingPeriod august = new BillingPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        // A misconfigured negative must never pull billing INTO the period.
        assertThat(InvoiceCalculations.billableFrom(august, -5)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void billableFrom_crossesAMonthBoundaryCorrectly() {
        // Weekly period ending Sun 7 Jun; +7d grace lands in the following week.
        BillingPeriod week = new BillingPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));
        assertThat(InvoiceCalculations.billableFrom(week, 7)).isEqualTo(LocalDate.of(2026, 6, 15));

        // Period ending on the last day of the year rolls into January.
        BillingPeriod december = new BillingPeriod(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        assertThat(InvoiceCalculations.billableFrom(december, 7)).isEqualTo(LocalDate.of(2027, 1, 8));
    }

    // -- Which periods a run bills --------------------------------------------
    // The scheduler used to bill only previousClosed(), once its grace had
    // passed. With a grace of a cycle or more that never happens while the
    // period is still the previous closed one, so no WEEKLY period was ever
    // billed with the default 7-day grace.

    private static final int GRACE = 7;
    private static final int CATCH_UP = 28;
    private static final LocalDate LONG_AGO = LocalDate.of(2000, 1, 1);

    private static BillingPeriod period(String start, String endInclusive) {
        return new BillingPeriod(LocalDate.parse(start), LocalDate.parse(endInclusive));
    }

    private static List<BillingPeriod> billable(BillingCycle cycle, String today, LocalDate billingStart) {
        return InvoiceCalculations.billablePeriods(cycle, LocalDate.parse(today), GRACE, CATCH_UP, billingStart);
    }

    @Test
    void billablePeriods_weekly_defaultGrace_billsTheWeekWhoseGraceEndedToday_notThePreviousClosedOne() {
        LocalDate mon15Jun = LocalDate.of(2026, 6, 15);

        // The week of 1-7 Jun becomes billable on Monday 15 Jun, when the
        // previous closed week is already 8-14 Jun: the old selection.
        assertThat(InvoiceCalculations.previousClosed(BillingCycle.WEEKLY, mon15Jun))
                .isEqualTo(period("2026-06-08", "2026-06-14"));
        assertThat(InvoiceCalculations.billableFrom(period("2026-06-08", "2026-06-14"), GRACE))
                .isAfter(mon15Jun);

        assertThat(billable(BillingCycle.WEEKLY, "2026-06-15", LocalDate.of(2026, 6, 1)))
                .containsExactly(period("2026-06-01", "2026-06-07"));
    }

    @Test
    void billablePeriods_weekly_nothingBeforeTheGraceEnds() {
        // Sunday 14 Jun: 1-7 Jun is still settling, and nothing earlier is billed.
        assertThat(billable(BillingCycle.WEEKLY, "2026-06-14", LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void billablePeriods_weekly_keepsEachWeekForTheCatchUpWindow_oldestFirst() {
        // Four weeks are in the window on Monday 15 Jun, oldest first.
        assertThat(billable(BillingCycle.WEEKLY, "2026-06-15", LONG_AGO)).containsExactly(
                period("2026-05-11", "2026-05-17"),
                period("2026-05-18", "2026-05-24"),
                period("2026-05-25", "2026-05-31"),
                period("2026-06-01", "2026-06-07"));

        // 1-7 Jun is tried for 28 days: its last day is Sunday 12 Jul ...
        assertThat(billable(BillingCycle.WEEKLY, "2026-07-12", LONG_AGO))
                .first().isEqualTo(period("2026-06-01", "2026-06-07"));
        // ... and on Monday 13 Jul it has dropped out and 29 Jun-5 Jul has come in.
        assertThat(billable(BillingCycle.WEEKLY, "2026-07-13", LONG_AGO)).containsExactly(
                period("2026-06-08", "2026-06-14"),
                period("2026-06-15", "2026-06-21"),
                period("2026-06-22", "2026-06-28"),
                period("2026-06-29", "2026-07-05"));
    }

    @Test
    void billablePeriods_weekly_acrossTheYearEnd() {
        assertThat(billable(BillingCycle.WEEKLY, "2027-01-11", LocalDate.of(2026, 12, 28)))
                .containsExactly(period("2026-12-28", "2027-01-03"));
    }

    @Test
    void billablePeriods_monthly_defaultGrace_isThePreviousMonthFromThe8th_oneMonthAtATime() {
        // Regression: MONTHLY bills the previous month from the 8th, as before.
        assertThat(billable(BillingCycle.MONTHLY, "2026-06-07", LONG_AGO)).isEmpty();
        assertThat(billable(BillingCycle.MONTHLY, "2026-06-08", LONG_AGO))
                .containsExactly(period("2026-05-01", "2026-05-31"));
        assertThat(billable(BillingCycle.MONTHLY, "2026-06-30", LONG_AGO))
                .containsExactly(period("2026-05-01", "2026-05-31"));

        // It keeps being tried into the next month (28 days in all, it used to
        // stop at the month end), and is never in a run beside June.
        assertThat(billable(BillingCycle.MONTHLY, "2026-07-05", LONG_AGO))
                .containsExactly(period("2026-05-01", "2026-05-31"));
        assertThat(billable(BillingCycle.MONTHLY, "2026-07-06", LONG_AGO)).isEmpty();
        assertThat(billable(BillingCycle.MONTHLY, "2026-07-08", LONG_AGO))
                .containsExactly(period("2026-06-01", "2026-06-30"));

        // Across the year end, and February's short month.
        assertThat(billable(BillingCycle.MONTHLY, "2027-01-08", LONG_AGO))
                .containsExactly(period("2026-12-01", "2026-12-31"));
        assertThat(billable(BillingCycle.MONTHLY, "2027-03-08", LONG_AGO))
                .containsExactly(period("2027-02-01", "2027-02-28"));
    }

    @Test
    void billablePeriods_nothingThatEndedBeforeTheBillingStartDate() {
        // Billing starts Monday 8 Jun: 1-7 Jun ended before it and is never billed.
        assertThat(billable(BillingCycle.WEEKLY, "2026-06-15", LocalDate.of(2026, 6, 8))).isEmpty();
        assertThat(billable(BillingCycle.WEEKLY, "2026-06-22", LocalDate.of(2026, 6, 8)))
                .containsExactly(period("2026-06-08", "2026-06-14"));
        assertThat(billable(BillingCycle.MONTHLY, "2026-06-08", LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void billablePeriods_thePeriodTheBillingStartFallsIn_startsOnIt() {
        assertThat(billable(BillingCycle.WEEKLY, "2026-06-15", LocalDate.of(2026, 6, 3)))
                .containsExactly(period("2026-06-03", "2026-06-07"));
        assertThat(billable(BillingCycle.MONTHLY, "2026-06-08", LocalDate.of(2026, 5, 20)))
                .containsExactly(period("2026-05-20", "2026-05-31"));
    }

    @Test
    void billablePeriods_anyGrace_walksBackToThePeriodsWhoseGraceHasPassed() {
        LocalDate mon15Jun = LocalDate.of(2026, 6, 15);

        // A 10-day grace: the latest billable week is 25-31 May (from 11 Jun).
        List<BillingPeriod> weeks = InvoiceCalculations.billablePeriods(
                BillingCycle.WEEKLY, mon15Jun, 10, CATCH_UP, LONG_AGO);
        assertThat(weeks).last().isEqualTo(period("2026-05-25", "2026-05-31"));
        assertThat(weeks).allSatisfy(p ->
                assertThat(InvoiceCalculations.billableFrom(p, 10)).isBeforeOrEqualTo(mon15Jun));

        // A grace longer than a month, which MONTHLY could not survive either.
        assertThat(InvoiceCalculations.billablePeriods(BillingCycle.MONTHLY, mon15Jun, 40, CATCH_UP, LONG_AGO))
                .containsExactly(period("2026-04-01", "2026-04-30"));

        // Zero grace bills last week from the Monday it closes.
        assertThat(InvoiceCalculations.billablePeriods(BillingCycle.WEEKLY, mon15Jun, 0, 1, LONG_AGO))
                .containsExactly(period("2026-06-08", "2026-06-14"));
    }

    @Test
    void billablePeriods_aCatchUpBelowOne_stillTriesEachPeriodOnItsFirstDay() {
        assertThat(InvoiceCalculations.billablePeriods(
                BillingCycle.WEEKLY, LocalDate.of(2026, 6, 15), GRACE, 0, LONG_AGO))
                .containsExactly(period("2026-06-01", "2026-06-07"));
        assertThat(InvoiceCalculations.billablePeriods(
                BillingCycle.WEEKLY, LocalDate.of(2026, 6, 16), GRACE, 0, LONG_AGO))
                .isEmpty();
    }
}
