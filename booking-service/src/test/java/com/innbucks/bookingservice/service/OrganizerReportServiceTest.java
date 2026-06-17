package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.report.BucketSize;
import com.innbucks.bookingservice.dto.report.CategoryRevenueDTO;
import com.innbucks.bookingservice.dto.report.EventRevenueDTO;
import com.innbucks.bookingservice.dto.report.RevenueSummaryDTO;
import com.innbucks.bookingservice.dto.report.SalesBucketDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.repository.OrganizerReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrganizerReportService}. Pure Mockito over a mocked
 * {@link OrganizerReportRepository}; the service does all aggregation in Java,
 * so these pin the financial maths (gross/refund/net, tickets, cash/points,
 * grouping, time-bucketing, CSV) and the date-window handling.
 */
class OrganizerReportServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID EVENT_A = UUID.randomUUID();
    private static final UUID EVENT_B = UUID.randomUUID();
    private static final UUID CAT_VIP = UUID.randomUUID();
    private static final UUID CAT_GA = UUID.randomUUID();

    private OrganizerReportService service(OrganizerReportRepository repo) {
        return new OrganizerReportService(repo, "USD");
    }

    private BookingItem item(UUID categoryId, String name, String price) {
        return BookingItem.builder()
                .id(UUID.randomUUID())
                .categoryId(categoryId).categoryName(name)
                .priceAtBooking(new BigDecimal(price))
                .build();
    }

    private Booking confirmed(UUID eventId, String total, String cash, String points,
                              LocalDateTime createdAt, BookingItem... items) {
        return Booking.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .tenantUserUuid(ORG)
                .status(Booking.BookingStatus.CONFIRMED)
                .confirmationNumber("INN-TEST-" + UUID.randomUUID())
                .phoneNumber("+263772000000")
                .totalAmount(new BigDecimal(total))
                .cashAmount(cash == null ? null : new BigDecimal(cash))
                .pointsUsed(points == null ? null : new BigDecimal(points))
                .createdAt(createdAt)
                .items(List.of(items))
                .build();
    }

    private Booking reversed(UUID eventId, String total, LocalDateTime createdAt) {
        return Booking.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .tenantUserUuid(ORG)
                .status(Booking.BookingStatus.CANCELLED)
                .availabilityReleased(true)
                .confirmationNumber("INN-REV-" + UUID.randomUUID())
                .totalAmount(new BigDecimal(total))
                .createdAt(createdAt)
                .items(List.of())
                .build();
    }

    @Test
    void revenueSummary_computesGrossRefundNetTicketsAndSplit() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        LocalDateTime t = LocalDate.of(2026, 5, 10).atTime(12, 0);
        // 2 confirmed: one pure-cash 2 tickets ($200), one cash+points 1 ticket ($100 = 80 cash + 20 pts)
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of(
                confirmed(EVENT_A, "200.00", "200.00", null, t,
                        item(CAT_VIP, "VIP", "100.00"), item(CAT_VIP, "VIP", "100.00")),
                confirmed(EVENT_A, "100.00", "80.00", "20.00", t,
                        item(CAT_GA, "General", "100.00"))));
        // 1 reversed: $50 refund
        when(repo.findReversed(eq(ORG), any(), any(), any())).thenReturn(List.of(
                reversed(EVENT_A, "50.00", t)));

        RevenueSummaryDTO s = service(repo).revenueSummary(ORG, EVENT_A,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertThat(s.confirmedBookings()).isEqualTo(2);
        assertThat(s.ticketsSold()).isEqualTo(3);
        assertThat(s.netRevenue()).isEqualByComparingTo("300.00");    // confirmed total
        assertThat(s.refundedAmount()).isEqualByComparingTo("50.00");
        assertThat(s.refundedBookings()).isEqualTo(1);
        assertThat(s.grossRevenue()).isEqualByComparingTo("350.00");  // confirmed + reversed
        assertThat(s.cashCollected()).isEqualByComparingTo("280.00");
        assertThat(s.pointsRedeemed()).isEqualByComparingTo("20.00");
        assertThat(s.averageOrderValue()).isEqualByComparingTo("150.00"); // 300 / 2
        assertThat(s.currency()).isEqualTo("USD");
        assertThat(s.eventId()).isEqualTo(EVENT_A);
    }

    @Test
    void revenueSummary_emptyPeriod_isAllZeroNotNullAndNoDivideByZero() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of());
        when(repo.findReversed(eq(ORG), any(), any(), any())).thenReturn(List.of());

        RevenueSummaryDTO s = service(repo).revenueSummary(ORG, null, null, null);

        assertThat(s.confirmedBookings()).isZero();
        assertThat(s.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(s.averageOrderValue()).isEqualByComparingTo("0.00"); // no ArithmeticException
    }

    @Test
    void revenueByEvent_groupsPerEventAndSortsByNetDescending() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        LocalDateTime t = LocalDate.of(2026, 5, 10).atTime(9, 0);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of(
                confirmed(EVENT_B, "100.00", "100.00", null, t, item(CAT_GA, "General", "100.00")),
                confirmed(EVENT_A, "300.00", "300.00", null, t,
                        item(CAT_VIP, "VIP", "150.00"), item(CAT_VIP, "VIP", "150.00"))));
        when(repo.findReversed(eq(ORG), any(), any(), any())).thenReturn(List.of(
                reversed(EVENT_A, "20.00", t)));

        List<EventRevenueDTO> rows = service(repo).revenueByEvent(ORG, null, null);

        assertThat(rows).hasSize(2);
        // EVENT_A first (net 300 > 100)
        assertThat(rows.get(0).eventId()).isEqualTo(EVENT_A);
        assertThat(rows.get(0).netRevenue()).isEqualByComparingTo("300.00");
        assertThat(rows.get(0).refundedAmount()).isEqualByComparingTo("20.00");
        assertThat(rows.get(0).grossRevenue()).isEqualByComparingTo("320.00");
        assertThat(rows.get(0).ticketsSold()).isEqualTo(2);
        assertThat(rows.get(1).eventId()).isEqualTo(EVENT_B);
        assertThat(rows.get(1).netRevenue()).isEqualByComparingTo("100.00");
    }

    @Test
    void revenueByCategory_sumsItemPricesPerClass() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        LocalDateTime t = LocalDate.of(2026, 5, 10).atTime(9, 0);
        when(repo.findConfirmedWithItems(eq(ORG), eq(EVENT_A), any(), any())).thenReturn(List.of(
                confirmed(EVENT_A, "300.00", "300.00", null, t,
                        item(CAT_VIP, "VIP", "150.00"), item(CAT_VIP, "VIP", "150.00")),
                confirmed(EVENT_A, "100.00", "100.00", null, t,
                        item(CAT_GA, "General", "100.00"))));

        List<CategoryRevenueDTO> rows = service(repo).revenueByCategory(ORG, EVENT_A, null, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).categoryName()).isEqualTo("VIP"); // 300 > 100, sorted desc
        assertThat(rows.get(0).ticketsSold()).isEqualTo(2);
        assertThat(rows.get(0).revenue()).isEqualByComparingTo("300.00");
        assertThat(rows.get(1).categoryName()).isEqualTo("General");
        assertThat(rows.get(1).revenue()).isEqualByComparingTo("100.00");
    }

    @Test
    void revenueByCategory_requiresEventId() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        assertThatThrownBy(() -> service(repo).revenueByCategory(ORG, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("eventId is required");
    }

    @Test
    void salesTimeSeries_bucketsByDay_andOmitsEmptyDays() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        LocalDateTime d4 = LocalDate.of(2026, 5, 4).atTime(10, 0);
        LocalDateTime d4b = LocalDate.of(2026, 5, 4).atTime(18, 0);
        LocalDateTime d6 = LocalDate.of(2026, 5, 6).atTime(11, 0);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of(
                confirmed(EVENT_A, "100.00", "100.00", null, d4, item(CAT_GA, "General", "100.00")),
                confirmed(EVENT_A, "200.00", "200.00", null, d4b,
                        item(CAT_VIP, "VIP", "100.00"), item(CAT_VIP, "VIP", "100.00")),
                confirmed(EVENT_A, "100.00", "100.00", null, d6, item(CAT_GA, "General", "100.00"))));

        List<SalesBucketDTO> series = service(repo).salesTimeSeries(ORG, null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), BucketSize.DAY);

        assertThat(series).hasSize(2); // May 4 and May 6; May 5 omitted (no sales)
        assertThat(series.get(0).bucketStart()).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(series.get(0).confirmedBookings()).isEqualTo(2);
        assertThat(series.get(0).ticketsSold()).isEqualTo(3);
        assertThat(series.get(0).revenue()).isEqualByComparingTo("300.00");
        assertThat(series.get(1).bucketStart()).isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    void salesTimeSeries_weekBucketKeysOnMonday() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        // 2026-05-06 is a Wednesday; its ISO week starts Monday 2026-05-04.
        LocalDateTime wed = LocalDate.of(2026, 5, 6).atTime(11, 0);
        LocalDateTime fri = LocalDate.of(2026, 5, 8).atTime(11, 0);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of(
                confirmed(EVENT_A, "100.00", "100.00", null, wed, item(CAT_GA, "General", "100.00")),
                confirmed(EVENT_A, "100.00", "100.00", null, fri, item(CAT_GA, "General", "100.00"))));

        List<SalesBucketDTO> series = service(repo).salesTimeSeries(ORG, null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), BucketSize.WEEK);

        assertThat(series).hasSize(1);
        assertThat(series.get(0).bucketStart()).isEqualTo(LocalDate.of(2026, 5, 4)); // Monday
        assertThat(series.get(0).confirmedBookings()).isEqualTo(2);
    }

    @Test
    void csv_hasHeaderAndOneRowPerConfirmedBookingWithMaskedPhone() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        LocalDateTime t = LocalDate.of(2026, 5, 2).atTime(15, 45);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of(
                confirmed(EVENT_A, "200.00", "200.00", null, t,
                        item(CAT_VIP, "VIP", "100.00"), item(CAT_VIP, "VIP", "100.00"))));

        String csv = service(repo).confirmedBookingsCsv(ORG, EVENT_A, null, null);
        String[] lines = csv.strip().split("\n");

        assertThat(lines[0]).isEqualTo(
                "confirmationNumber,eventId,createdAt,ticketsSold,totalAmount,cashAmount,pointsUsed,phone");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(",2,200.00,200.00,0.00,");
        assertThat(lines[1]).doesNotContain("+263772000000"); // raw phone must be masked
    }

    @Test
    void resolveRange_rejectsFromAfterTo() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        assertThatThrownBy(() -> service(repo).revenueSummary(ORG, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void resolveRange_defaultsToLast30DaysWhenUnset() {
        OrganizerReportRepository repo = mock(OrganizerReportRepository.class);
        when(repo.findConfirmedWithItems(eq(ORG), any(), any(), any())).thenReturn(List.of());
        when(repo.findReversed(eq(ORG), any(), any(), any())).thenReturn(List.of());

        service(repo).revenueSummary(ORG, null, null, null);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(repo).findConfirmedWithItems(eq(ORG), any(), start.capture(), end.capture());
        // [from 00:00, (to+1) 00:00) — 30 calendar days inclusive == 30 day-starts span.
        long days = ChronoUnit.DAYS.between(start.getValue(), end.getValue());
        assertThat(days).isEqualTo(30);
    }
}
