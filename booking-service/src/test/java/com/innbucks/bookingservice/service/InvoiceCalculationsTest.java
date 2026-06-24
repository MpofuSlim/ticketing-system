package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

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
}
