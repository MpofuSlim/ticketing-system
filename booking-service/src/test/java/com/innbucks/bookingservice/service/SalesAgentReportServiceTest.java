package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.AgentHandoverRequestDTO;
import com.innbucks.bookingservice.dto.report.AgentHandoverDTO;
import com.innbucks.bookingservice.dto.report.AgentSalesReportDTO;
import com.innbucks.bookingservice.dto.report.SalesAgentsReportDTO;
import com.innbucks.bookingservice.entity.AgentCashHandover;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.repository.AgentCashHandoverRepository;
import com.innbucks.bookingservice.repository.OrganizerReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sales-agent figures (V36): cash taken counts cash sales made in the
 * window, cash voided takes voids in the window back out, online sales are
 * paid online ones, commission is only on CONFIRMED sales, handovers reduce
 * what is owed, and every read is scoped to the caller.
 */
class SalesAgentReportServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TENDAI = UUID.randomUUID();
    private static final UUID CHIPO = UUID.randomUUID();
    private static final UUID TENDAI_ACCOUNT = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    private final OrganizerReportRepository reports = mock(OrganizerReportRepository.class);
    private final AgentCashHandoverRepository handovers = mock(AgentCashHandoverRepository.class);

    private SalesAgentReportService service() {
        return new SalesAgentReportService(reports, handovers, new MarketTimeZone("ZW"), "USD");
    }

    private static Booking sale(UUID agent, String name, String rail, Booking.BookingStatus status, String total,
                                String commission, LocalDateTime at, int tickets) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(EVENT)
                .tenantUserUuid(ORG)
                .confirmationNumber("INN-" + UUID.randomUUID().toString().substring(0, 6))
                .customerName("Rufaro Moyo")
                .phoneNumber("+263771234567")
                .status(status)
                .totalAmount(new BigDecimal(total))
                .paymentRail(rail)
                .soldByAgentId(agent)
                .soldByUserUuid(TENDAI_ACCOUNT)
                .soldByName(name)
                .agentCommissionPercent(new BigDecimal("5.00"))
                .agentCommissionAmount(commission == null ? null : new BigDecimal(commission))
                .createdAt(at)
                .items(new ArrayList<>())
                .build();
        for (int i = 0; i < tickets; i++) {
            b.getItems().add(BookingItem.builder().booking(b).categoryName("VIP").build());
        }
        return b;
    }

    private static Booking voided(Booking b, LocalDateTime at) {
        b.setStatus(Booking.BookingStatus.CANCELLED);
        b.setCancelReason(Booking.CancelReason.CASH_VOIDED);
        b.setVoidedAt(at);
        b.setVoidedByName("organizer@example.com");
        return b;
    }

    private static AgentCashHandover handover(UUID agent, String amount, UUID eventId) {
        return AgentCashHandover.builder().id(UUID.randomUUID()).organizerUuid(ORG).agentId(agent).eventId(eventId)
                .amount(new BigDecimal(amount)).currency("USD").receivedAt(DAY.atTime(12, 0))
                .recordedBy("organizer@example.com").note("Midday cash").createdAt(DAY.atTime(12, 1)).build();
    }

    @Test
    void mySales_addsUpTheAgentsOwnSales_andShowsOnlyAMaskedPhone() {
        Booking cash = sale(TENDAI, "Tendai Moyo", "CASH", Booking.BookingStatus.CONFIRMED, "300.00", "15.00",
                DAY.atTime(9, 15), 2);
        Booking online = sale(TENDAI, "Tendai Moyo", "ECOCASH", Booking.BookingStatus.CONFIRMED, "150.00", "7.50",
                DAY.atTime(10, 2), 1);
        Booking voidedCash = voided(sale(TENDAI, "Tendai Moyo", "CASH", Booking.BookingStatus.CONFIRMED, "150.00",
                "7.50", DAY.atTime(8, 40), 1), DAY.atTime(8, 55));
        Booking unpaid = sale(TENDAI, "Tendai Moyo", null, Booking.BookingStatus.PENDING, "150.00", null,
                DAY.atTime(11, 0), 1);
        Booking expired = sale(TENDAI, "Tendai Moyo", null, Booking.BookingStatus.CANCELLED, "150.00", null,
                DAY.atTime(7, 0), 1);
        expired.setCancelReason(Booking.CancelReason.EXPIRED);
        when(reports.findAgentSalesWithItems(isNull(), eq(TENDAI_ACCOUNT), eq(EVENT), any(), any()))
                .thenReturn(List.of(expired, voidedCash, cash, online, unpaid));
        when(reports.findAgentIdsSoldBy(TENDAI_ACCOUNT)).thenReturn(List.of(TENDAI));
        when(handovers.findLiveForAgents(eq(List.of(TENDAI)), eq(EVENT), any(), any()))
                .thenReturn(List.of(handover(TENDAI, "200.00", EVENT)));

        AgentSalesReportDTO report = service().mySales(TENDAI_ACCOUNT, EVENT, null, null);

        assertThat(report.entries()).extracting(AgentSalesReportDTO.Entry::status)
                .containsExactly("PENDING", "CONFIRMED", "CONFIRMED", "VOIDED", "CANCELLED");
        assertThat(report.entries()).allSatisfy(e -> assertThat(e.customerPhone()).isEqualTo("****4567"));
        AgentSalesReportDTO.Entry voidedEntry = report.entries().get(3);
        assertThat(voidedEntry.commission()).as("a voided sale earns nothing").isNull();
        assertThat(voidedEntry.paymentMethod()).isEqualTo("CASH");
        assertThat(report.entries().get(0).paymentMethod()).as("an unpaid online sale has no method yet").isNull();
        AgentSalesReportDTO.Totals t = report.totals();
        assertThat(t.ticketsSold()).isEqualTo(3);
        assertThat(t.cashTaken()).isEqualByComparingTo("450.00");
        assertThat(t.cashVoided()).isEqualByComparingTo("150.00");
        assertThat(t.onlineSales()).isEqualByComparingTo("150.00");
        assertThat(t.commissionEarned()).isEqualByComparingTo("22.50");
        assertThat(t.handedOver()).isEqualByComparingTo("200.00");
        assertThat(t.cashOwed()).isEqualByComparingTo("100.00");
        assertThat(t.currency()).isEqualTo("USD");
        assertThat(report.handovers()).singleElement()
                .satisfies(h -> assertThat(h.recordedBy()).as("the organizer's business").isNull());
    }

    @Test
    void mySales_ofAnAccountThatNeverSold_asksForNoHandovers() {
        when(reports.findAgentIdsSoldBy(TENDAI_ACCOUNT)).thenReturn(List.of());

        AgentSalesReportDTO report = service().mySales(TENDAI_ACCOUNT, null, null, null);

        assertThat(report.entries()).isEmpty();
        assertThat(report.totals().cashOwed()).isEqualByComparingTo("0.00");
        verify(handovers, never()).findLiveForAgents(anyCollection(), any(), any(), any());
    }

    @Test
    void aWindow_countsASaleOnItsDay_andAVoidOnItsOwn() {
        Booking madeYesterdayVoidedToday = voided(sale(TENDAI, "Tendai Moyo", "CASH",
                Booking.BookingStatus.CONFIRMED, "50.00", "2.50", DAY.minusDays(1).atTime(16, 0), 1),
                DAY.atTime(7, 0));
        when(reports.findAgentSalesWithItems(any(), any(), any(), any(), any()))
                .thenReturn(List.of(madeYesterdayVoidedToday));
        when(reports.findAgentIdsSoldBy(any())).thenReturn(List.of());

        AgentSalesReportDTO report = service().mySales(TENDAI_ACCOUNT, null, DAY, DAY);

        assertThat(report.totals().cashTaken()).isEqualByComparingTo("0.00");
        assertThat(report.totals().cashVoided()).isEqualByComparingTo("50.00");
        assertThat(report.totals().cashOwed()).isEqualByComparingTo("-50.00");
        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reports).findAgentSalesWithItems(isNull(), eq(TENDAI_ACCOUNT), isNull(), start.capture(),
                end.capture());
        assertThat(start.getValue()).isEqualTo(DAY.atStartOfDay());
        assertThat(end.getValue()).isEqualTo(DAY.plusDays(1).atStartOfDay());
    }

    @Test
    void anInvertedWindow_isA400() {
        assertThatThrownBy(() -> service().mySales(TENDAI_ACCOUNT, null, DAY, DAY.minusDays(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("'from' (2026-11-20) must not be after 'to' (2026-11-19).");
    }

    @Test
    void salesAgents_isOneRowPerAgent_byName_withAHandoverOnlyAgentNamedFromTheirSales() {
        when(reports.findAgentSalesWithItems(eq(ORG), isNull(), isNull(), any(), any())).thenReturn(List.of(
                sale(TENDAI, "Tendai M", "CASH", Booking.BookingStatus.CONFIRMED, "300.00", "15.00",
                        DAY.atTime(9, 0), 2),
                sale(TENDAI, "Tendai Moyo", "ZIMSWITCH_CARD", Booking.BookingStatus.CONFIRMED, "150.00", "7.50",
                        DAY.atTime(10, 0), 1),
                voided(sale(TENDAI, "Tendai Moyo", "CASH", Booking.BookingStatus.CONFIRMED, "150.00", "7.50",
                        DAY.atTime(10, 30), 1), DAY.atTime(10, 45))));
        when(handovers.findLive(eq(ORG), isNull(), any(), any())).thenReturn(List.of(
                handover(TENDAI, "200.00", null), handover(CHIPO, "600.00", EVENT)));
        when(reports.findAgentNames(List.of(CHIPO))).thenReturn(List.of(agentName(CHIPO, "Chipo Banda")));

        SalesAgentsReportDTO report = service().salesAgents(ORG, null, null, null);

        assertThat(report.agents()).extracting(SalesAgentsReportDTO.Agent::displayName)
                .containsExactly("Chipo Banda", "Tendai Moyo");
        SalesAgentsReportDTO.Agent chipo = report.agents().get(0);
        assertThat(chipo.ticketsSold()).isZero();
        assertThat(chipo.handedOver()).isEqualByComparingTo("600.00");
        assertThat(chipo.cashOwed()).isEqualByComparingTo("-600.00");
        SalesAgentsReportDTO.Agent tendai = report.agents().get(1);
        assertThat(tendai.agentId()).isEqualTo(TENDAI);
        assertThat(tendai.ticketsSold()).isEqualTo(3);
        assertThat(tendai.cashTaken()).isEqualByComparingTo("450.00");
        assertThat(tendai.cashVoided()).isEqualByComparingTo("150.00");
        assertThat(tendai.onlineSales()).isEqualByComparingTo("150.00");
        assertThat(tendai.commissionOwed()).isEqualByComparingTo("22.50");
        assertThat(tendai.handedOver()).isEqualByComparingTo("200.00");
        assertThat(tendai.cashOwed()).isEqualByComparingTo("100.00");
        assertThat(report.totals().cashTaken()).isEqualByComparingTo("450.00");
        assertThat(report.totals().handedOver()).isEqualByComparingTo("800.00");
        assertThat(report.totals().cashOwed()).isEqualByComparingTo("-500.00");
        assertThat(report.totals().commissionOwed()).isEqualByComparingTo("22.50");
    }

    private static OrganizerReportRepository.AgentName agentName(UUID id, String name) {
        return new OrganizerReportRepository.AgentName() {
            @Override
            public UUID getAgentId() {
                return id;
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    // ---- handovers --------------------------------------------------------------

    private static AgentHandoverRequestDTO handoverRequest(String amount, UUID eventId, String receivedAt,
                                                           String note) {
        AgentHandoverRequestDTO r = new AgentHandoverRequestDTO();
        r.setAmount(new BigDecimal(amount));
        r.setEventId(eventId);
        r.setReceivedAt(receivedAt);
        r.setNote(note);
        return r;
    }

    @Test
    void aHandover_isOnlyForAnAgentWhoSoldForTheCaller_andForAnEventTheySoldFor() {
        when(reports.agentHasSoldFor(ORG, TENDAI, null)).thenReturn(false);
        assertThatThrownBy(() -> service().recordHandover(ORG, TENDAI, handoverRequest("10", null, null, null),
                ORG, "organizer@example.com"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("AGENT_NOT_FOUND");
                });

        when(reports.agentHasSoldFor(ORG, TENDAI, null)).thenReturn(true);
        when(reports.agentHasSoldFor(ORG, TENDAI, EVENT)).thenReturn(false);
        assertThatThrownBy(() -> service().recordHandover(ORG, TENDAI, handoverRequest("10", EVENT, null, null),
                ORG, "organizer@example.com"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("HANDOVER_EVENT_INVALID");
                });
        verify(handovers, never()).save(any());
    }

    @Test
    void aHandover_isRecordedInUtc_fromTheOrganizersLocalTime_withACleanNote() {
        when(reports.agentHasSoldFor(ORG, TENDAI, null)).thenReturn(true);
        when(reports.agentHasSoldFor(ORG, TENDAI, EVENT)).thenReturn(true);
        when(handovers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentHandoverDTO recorded = service().recordHandover(ORG, TENDAI,
                handoverRequest("1500", EVENT, "2026-11-01T18:30:00", "Friday\ncash‮ "), ORG,
                "organizer@example.com");

        ArgumentCaptor<AgentCashHandover> saved = ArgumentCaptor.forClass(AgentCashHandover.class);
        verify(handovers).save(saved.capture());
        AgentCashHandover h = saved.getValue();
        assertThat(h.getReceivedAt()).as("Harare is UTC+2").isEqualTo(LocalDateTime.of(2026, 11, 1, 16, 30));
        assertThat(h.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(h.getAmount().scale()).isEqualTo(2);
        assertThat(h.getCurrency()).isEqualTo("USD");
        assertThat(h.getOrganizerUuid()).isEqualTo(ORG);
        assertThat(h.getNote()).isEqualTo("Friday cash");
        assertThat(h.getRecordedBy()).isEqualTo("organizer@example.com");
        assertThat(recorded.recordedBy()).isEqualTo("organizer@example.com");
    }

    @Test
    void aHandover_withNoTime_isNow_andOneInTheFutureOrUnreadableIsRefused() {
        when(reports.agentHasSoldFor(ORG, TENDAI, null)).thenReturn(true);
        when(handovers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service().recordHandover(ORG, TENDAI, handoverRequest("5", null, null, "  "), ORG, null);

        ArgumentCaptor<AgentCashHandover> saved = ArgumentCaptor.forClass(AgentCashHandover.class);
        verify(handovers).save(saved.capture());
        assertThat(saved.getValue().getReceivedAt()).isAfterOrEqualTo(before.withNano(0));
        assertThat(saved.getValue().getNote()).as("blank is no note").isNull();

        String tomorrow = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0) + "Z";
        assertThatThrownBy(() -> service().recordHandover(ORG, TENDAI, handoverRequest("5", null, tomorrow, null),
                ORG, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> service().recordHandover(ORG, TENDAI, handoverRequest("5", null, "yesterday", null),
                ORG, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("2026-11-14T18:30:00");
    }

    @Test
    void deletingAHandover_isSoft_idempotent_andA404ForAnyoneElses() {
        UUID id = UUID.randomUUID();
        AgentCashHandover h = handover(TENDAI, "50.00", null);
        h.setId(id);
        h.setDeletedAt(DAY.atTime(17, 35));
        h.setDeletedBy("organizer@example.com");
        when(handovers.softDelete(eq(id), eq(ORG), eq(TENDAI), any(), eq(ORG), eq("organizer@example.com")))
                .thenReturn(1);
        when(handovers.findByIdAndOrganizerUuidAndAgentId(id, ORG, TENDAI)).thenReturn(Optional.of(h));

        AgentHandoverDTO deleted = service().deleteHandover(ORG, TENDAI, id, ORG, "organizer@example.com");
        assertThat(deleted.deletedAt()).isEqualTo(DAY.atTime(17, 35));
        assertThat(deleted.deletedBy()).isEqualTo("organizer@example.com");

        // Again: nothing changes, the same row is answered.
        when(handovers.softDelete(any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertThat(service().deleteHandover(ORG, TENDAI, id, ORG, "someone-else@example.com").deletedBy())
                .isEqualTo("organizer@example.com");

        UUID other = UUID.randomUUID();
        when(handovers.findByIdAndOrganizerUuidAndAgentId(id, other, TENDAI)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().deleteHandover(other, TENDAI, id, other, "x"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("HANDOVER_NOT_FOUND"));
    }

    @Test
    void theOrganizersList_showsDeletedHandoversWithWhoDeletedThem() {
        AgentCashHandover h = handover(TENDAI, "50.00", null);
        h.setDeletedAt(DAY.atTime(17, 35));
        h.setDeletedBy("organizer@example.com");
        when(handovers.findAllForAgent(ORG, TENDAI)).thenReturn(List.of(h));

        assertThat(service().listHandovers(ORG, TENDAI)).singleElement().satisfies(dto -> {
            assertThat(dto.deletedBy()).isEqualTo("organizer@example.com");
            assertThat(dto.recordedBy()).isEqualTo("organizer@example.com");
        });
    }

    @Test
    void aNote_losesControlAndInvisibleCharacters_andIsCapped() {
        assertThat(SalesAgentReportService.cleanNote(null)).isNull();
        assertThat(SalesAgentReportService.cleanNote(" ​ ")).isNull();
        assertThat(SalesAgentReportService.cleanNote("a b\tc")).isEqualTo("a b c");
        assertThat(SalesAgentReportService.cleanNote("x".repeat(600))).hasSize(500);
    }
}
