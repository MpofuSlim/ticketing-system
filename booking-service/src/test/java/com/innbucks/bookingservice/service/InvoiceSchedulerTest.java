package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.service.InvoiceCalculations.BillingPeriod;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which periods the scheduler invoices on which day, run on the shipped
 * defaults (7-day settle grace, 28-day catch-up).
 *
 * <p>The scheduler used to bill only the previous closed period, once its
 * grace had passed. A WEEKLY period with a 7-day grace is billable from the
 * Monday after next, when it is no longer the previous closed week, so no
 * weekly organizer was ever invoiced. MONTHLY worked only because a month
 * stays the previous one for about 30 days.
 *
 * <p>{@link InvoiceService} is mocked: whether a day is already invoiced, and
 * the invoice itself, are its job ({@code InvoiceServiceTest},
 * {@code InvoiceAggregationIT}).
 */
class InvoiceSchedulerTest {

    private InvoiceService invoiceService;
    private BillingConfigService billingConfig;
    private InvoiceProperties properties;
    private InvoiceScheduler scheduler;

    private final UUID weeklyOrganizer = UUID.randomUUID();
    private final UUID monthlyOrganizer = UUID.randomUUID();
    private final List<EventRevenueLine> weeklyLines =
            List.of(new EventRevenueLine(UUID.randomUUID(), 2, 3, new BigDecimal("300.00")));
    private final List<EventRevenueLine> monthlyLines =
            List.of(new EventRevenueLine(UUID.randomUUID(), 1, 1, new BigDecimal("80.00")));

    @BeforeEach
    void setUp() {
        invoiceService = mock(InvoiceService.class);
        billingConfig = mock(BillingConfigService.class);
        properties = new InvoiceProperties(); // the shipped defaults
        properties.setBillingStartDate("2026-01-01");
        scheduler = new InvoiceScheduler(invoiceService, mock(InvoiceNotifier.class), billingConfig, properties);

        when(billingConfig.resolve(weeklyOrganizer)).thenReturn(terms(weeklyOrganizer, BillingCycle.WEEKLY));
        when(billingConfig.resolve(monthlyOrganizer)).thenReturn(terms(monthlyOrganizer, BillingCycle.MONTHLY));
        // Both organizers have an event that ended in every period asked about.
        when(invoiceService.aggregateLinesForEndedEvents(isNull(), any(), any()))
                .thenAnswer(inv -> Optional.of(linesOfBoth()));
        when(invoiceService.generateScheduled(any(), any(), any())).thenReturn(Optional.of(new EventInvoice()));
    }

    @Test
    void theseTestsRunOnTheShippedDefaults() {
        InvoiceProperties shipped = new InvoiceProperties();
        assertThat(shipped.getSettleGraceDays()).isEqualTo(7);
        assertThat(shipped.getCatchUpDays()).isEqualTo(28);
        assertThat(shipped.getBillingStartDate()).isBlank();
    }

    // -- WEEKLY ----------------------------------------------------------------

    @Test
    void weekly_defaultGrace_invoicesTheWeekOnTheMondayItsGraceEnds() {
        properties.setBillingStartDate("2026-06-01");

        runOn("2026-06-14"); // Sunday: 1-7 Jun is still settling
        verify(invoiceService, never()).generateScheduled(eq(weeklyOrganizer), any(), any());

        runOn("2026-06-15"); // Monday: its 7-day grace has ended
        verify(invoiceService).generateScheduled(weeklyOrganizer, period("2026-06-01", "2026-06-07"), weeklyLines);
        // The monthly organizer's events in that week wait for the monthly pass.
        verify(invoiceService, never()).generateScheduled(eq(monthlyOrganizer), argThat(this::isAWeek), any());
    }

    @Test
    void weekly_defaultGrace_overAQuarter_everyWeekIsTriedFromTheDayItsGraceEnds() {
        properties.setBillingStartDate("2026-06-01");
        Map<BillingPeriod, LocalDate> firstTried = recordFirstTry(weeklyOrganizer);

        for (LocalDate day = LocalDate.of(2026, 6, 1); !day.isAfter(LocalDate.of(2026, 8, 31)); day = day.plusDays(1)) {
            runOn(day);
        }

        // Every week from 1-7 Jun to 17-23 Aug (the last whose grace ends by
        // 31 Aug), each first tried on the Monday after next, never earlier.
        List<BillingPeriod> expected = new ArrayList<>();
        for (LocalDate monday = LocalDate.of(2026, 6, 1); !monday.isAfter(LocalDate.of(2026, 8, 17));
             monday = monday.plusWeeks(1)) {
            expected.add(new BillingPeriod(monday, monday.plusDays(6)));
        }
        assertThat(firstTried.keySet()).containsExactlyElementsOf(expected);
        firstTried.forEach((week, day) -> assertThat(day)
                .as("first try of %s", week)
                .isEqualTo(week.endInclusive().plusDays(8)));
    }

    @Test
    void weekly_aWeekWhoseEndedEventsCouldNotBeResolved_isInvoicedOnALaterRun() {
        properties.setBillingStartDate("2026-06-01");
        BillingPeriod week = period("2026-06-01", "2026-06-07");
        when(invoiceService.aggregateLinesForEndedEvents(null, week.start(), week.endInclusive()))
                .thenReturn(Optional.empty());

        runOn("2026-06-15");
        verify(invoiceService, never()).generateScheduled(any(), eq(week), any());

        // event-service answers again a few days later; the week is still in
        // the catch-up window, so it is billed.
        when(invoiceService.aggregateLinesForEndedEvents(null, week.start(), week.endInclusive()))
                .thenReturn(Optional.of(linesOfBoth()));
        runOn("2026-06-18");
        verify(invoiceService).generateScheduled(weeklyOrganizer, week, weeklyLines);
    }

    @Test
    void weekly_eachOrganizersCycleIsReadOncePerRun() {
        runOn("2026-06-15"); // four weeks in the catch-up window, plus May

        verify(invoiceService, times(4)).generateScheduled(eq(weeklyOrganizer), any(), any());
        verify(billingConfig, times(1)).resolve(weeklyOrganizer);
        verify(billingConfig, times(1)).resolve(monthlyOrganizer);
    }

    // -- MONTHLY (regression) -------------------------------------------------

    @Test
    void monthly_defaultGrace_invoicesThePreviousMonthFromThe8th_asBefore() {
        runOn("2026-06-07");
        verify(invoiceService, never()).generateScheduled(eq(monthlyOrganizer), any(), any());

        runOn("2026-06-08");
        verify(invoiceService).generateScheduled(monthlyOrganizer, period("2026-05-01", "2026-05-31"), monthlyLines);
        // The weekly organizer's events in May wait for the weekly pass.
        verify(invoiceService, never()).generateScheduled(eq(weeklyOrganizer), argThat(this::isAMonth), any());
    }

    @Test
    void monthly_defaultGrace_overAQuarter_eachMonthIsTriedFromThe8th_oneMonthPerRun() {
        properties.setBillingStartDate("2026-05-01");
        Map<BillingPeriod, LocalDate> firstTried = new LinkedHashMap<>();
        Map<LocalDate, Integer> monthsPerRun = new LinkedHashMap<>();
        when(invoiceService.generateScheduled(eq(monthlyOrganizer), any(), any())).thenAnswer(inv -> {
            firstTried.putIfAbsent(inv.getArgument(1), today);
            monthsPerRun.merge(today, 1, Integer::sum);
            return Optional.of(new EventInvoice());
        });

        for (LocalDate day = LocalDate.of(2026, 6, 1); !day.isAfter(LocalDate.of(2026, 9, 30)); day = day.plusDays(1)) {
            runOn(day);
        }

        assertThat(firstTried).containsExactly(
                Map.entry(period("2026-05-01", "2026-05-31"), LocalDate.of(2026, 6, 8)),
                Map.entry(period("2026-06-01", "2026-06-30"), LocalDate.of(2026, 7, 8)),
                Map.entry(period("2026-07-01", "2026-07-31"), LocalDate.of(2026, 8, 8)),
                Map.entry(period("2026-08-01", "2026-08-31"), LocalDate.of(2026, 9, 8)));
        assertThat(monthsPerRun.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
    }

    // -- Billing start date (forward only) ------------------------------------

    @Test
    void noBillingStartDate_invoicesNothing() {
        properties.setBillingStartDate("");
        runOn("2026-06-15");

        properties.setBillingStartDate("15/06/2026");
        runOn("2026-06-15");

        verifyNoInteractions(invoiceService);
        verifyNoInteractions(billingConfig);
    }

    @Test
    void theBillingStartDate_isTheFirstDayInvoiced() {
        properties.setBillingStartDate("2026-06-03"); // a Wednesday

        runOn("2026-06-15");

        // The week it falls in is invoiced from that day, and nothing earlier.
        verify(invoiceService).aggregateLinesForEndedEvents(null, LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 7));
        verify(invoiceService).generateScheduled(weeklyOrganizer, period("2026-06-03", "2026-06-07"), weeklyLines);
        verify(invoiceService, times(1)).generateScheduled(eq(weeklyOrganizer), any(), any());
        // May ended before it: the monthly pass has nothing to bill.
        verify(invoiceService, never()).generateScheduled(eq(monthlyOrganizer), any(), any());
    }

    // -- helpers ---------------------------------------------------------------

    private LocalDate today;

    private void runOn(String day) {
        runOn(LocalDate.parse(day));
    }

    private void runOn(LocalDate day) {
        today = day;
        scheduler.setClock(Clock.fixed(day.atTime(1, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        scheduler.generateDue();
    }

    /** Records the first day each period is tried for {@code organizer}, in order. */
    private Map<BillingPeriod, LocalDate> recordFirstTry(UUID organizer) {
        Map<BillingPeriod, LocalDate> firstTried = new LinkedHashMap<>();
        when(invoiceService.generateScheduled(eq(organizer), any(), any())).thenAnswer(inv -> {
            firstTried.putIfAbsent(inv.getArgument(1), today);
            return Optional.of(new EventInvoice());
        });
        return firstTried;
    }

    private Map<UUID, List<EventRevenueLine>> linesOfBoth() {
        Map<UUID, List<EventRevenueLine>> byOrganizer = new LinkedHashMap<>();
        byOrganizer.put(weeklyOrganizer, weeklyLines);
        byOrganizer.put(monthlyOrganizer, monthlyLines);
        return byOrganizer;
    }

    private boolean isAWeek(BillingPeriod p) {
        return p != null && p.endInclusive().equals(p.start().plusDays(6));
    }

    private boolean isAMonth(BillingPeriod p) {
        return p != null && p.start().getDayOfMonth() == 1
                && p.endInclusive().equals(p.start().plusMonths(1).minusDays(1));
    }

    private static BillingPeriod period(String start, String endInclusive) {
        return new BillingPeriod(LocalDate.parse(start), LocalDate.parse(endInclusive));
    }

    private static OrganizerBillingConfig terms(UUID organizer, BillingCycle cycle) {
        return OrganizerBillingConfig.builder()
                .organizerUuid(organizer)
                .commissionRate(new BigDecimal("10.0"))
                .billingCycle(cycle)
                .currency("USD")
                .build();
    }
}
