package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.dto.SellerAccessDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_PROMO_CODE_NOT_ALLOWED;
import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_SELLER_UNCHECKED;
import static com.innbucks.bookingservice.controller.SalesAgentReportController.EXAMPLE_AGENT_NOT_FOUND;
import static com.innbucks.bookingservice.controller.SalesAgentReportController.EXAMPLE_HANDOVER_EVENT_INVALID;
import static com.innbucks.bookingservice.controller.SalesAgentReportController.EXAMPLE_HANDOVER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sales agents (V36) through the real filter chain and Postgres: a burst of
 * one agent's sales sells exactly their ticket cap and holds nothing for the
 * rest; an agent's sale names the agent and earns its commission at the
 * confirm, cash or online; the agent sees only their own sales; the organizer
 * sees every agent, records and deletes handovers; and the migration's
 * columns, index and checks are in place. The documented refusals are
 * compared strictly with their Swagger examples.
 */
class SalesAgentsPostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();
    private final UUID agentRow = UUID.randomUUID();
    private final UUID agentAccount = UUID.randomUUID();
    private final AtomicReference<Integer> cap = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        category(eventId, vip, "VIP", 100);
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer).build()));
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenAnswer(invocation -> ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).title("Jazz on the Lawn")
                        .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(30)).build()));
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(99);
        when(eventServiceClient.releaseAvailability(eq(eventId), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));
        cap.set(null);
        // Made once, read live: re-stubbing races the async listeners a sale starts.
        when(eventServiceClient.sellerAccess(any(), any(), any())).thenAnswer(call -> {
            boolean allowed = agentAccount.equals(call.getArgument(0)) && eventId.equals(call.getArgument(1));
            return ApiResult.ok("Seller access", allowed
                    ? SellerAccessDTO.builder().allowed(true).agentId(agentRow).organizerUuid(organizer)
                            .displayName("Tendai Moyo").ticketCap(cap.get())
                            .commissionPercent(new BigDecimal("5.00")).build()
                    : SellerAccessDTO.builder().allowed(false).build());
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM agent_cash_handovers WHERE organizer_uuid = ?", organizer);
        deleteSeeded();
    }

    /** The agent's InnBucks app session: CUSTOMER, their account id, no email. */
    private String agentBearer() {
        return bearer(null, List.of("CUSTOMER"),
                Map.of("userUuid", agentAccount.toString(), "phoneNumber", "+263772000111"));
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString(), "userUuid", organizer.toString()));
    }

    private ResultActions sellActions(String bearer, String phone, int tickets, String paymentMethod, String promo)
            throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int k = 0; k < tickets; k++) {
            seats.append(k == 0 ? "" : ",").append("{\"categoryId\":\"").append(vip).append("\"}");
        }
        String body = """
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"%s",%s%s"seats":[%s]}
                """.formatted(eventId, phone,
                paymentMethod == null ? "" : "\"paymentMethod\":\"" + paymentMethod + "\",",
                promo == null ? "" : "\"promoCode\":\"" + promo + "\",", seats);
        return mvc.perform(post("/bookings/box-office").header("Authorization", bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult sell(String bearer, String phone, int tickets, String paymentMethod, String promo)
            throws Exception {
        return sellActions(bearer, phone, tickets, paymentMethod, promo).andReturn();
    }

    private UUID sold(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"));
    }

    private long agentTickets() {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.sold_by_agent_id = ? AND b.event_id = ? AND b.status <> 'CANCELLED'""",
                Long.class, agentRow, eventId);
        return n == null ? 0 : n;
    }

    @Test
    void aBurstOfOneAgentsSales_sellsExactlyTheCap_andHoldsNothingForTheRest() throws Exception {
        cap.set(5);
        int sales = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < sales; i++) {
                String phone = "+26377200%04d".formatted(i);
                results.add(pool.submit(() -> {
                    start.await();
                    return sell(agentBearer(), phone, 1, null, null);
                }));
            }
            start.countDown();
            int created = 0;
            int capped = 0;
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                int code = r.getResponse().getStatus();
                if (code == 201) {
                    created++;
                } else {
                    assertThat(code).as(r.getResponse().getContentAsString()).isEqualTo(409);
                    assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.data.errorCode"))
                            .isEqualTo("AGENT_CAP_REACHED");
                    assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.data.cap"))
                            .isEqualTo(5);
                    assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.data.remaining"))
                            .isZero();
                    capped++;
                }
            }
            assertThat(created).isEqualTo(5);
            assertThat(capped).isEqualTo(15);
        } finally {
            pool.shutdownNow();
        }
        assertThat(agentTickets()).isEqualTo(5);
        // The refused sales held nothing: no booking row, no stock.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(5);
        assertThat(remaining(vip)).isEqualTo(95);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bookings WHERE event_id = ? AND status = 'CONFIRMED' AND payment_rail = 'CASH'
                  AND sold_by_user_uuid = ? AND sold_by_name = 'Tendai Moyo' AND agent_commission_percent = 5.00
                  AND agent_commission_amount = 0.50""", Integer.class, eventId, agentAccount)).isEqualTo(5);
    }

    @Test
    void aVoidedSale_freesItsPlaceUnderTheCap() throws Exception {
        cap.set(1);
        UUID first = sold(sell(agentBearer(), "+263772000001", 1, null, null));
        MvcResult second = sell(agentBearer(), "+263772000002", 1, null, null);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);

        mvc.perform(post("/bookings/box-office/" + first + "/void").header("Authorization", organizerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong event\"}"))
                .andExpect(status().isOk());

        sold(sell(agentBearer(), "+263772000002", 1, null, null));
        assertThat(agentTickets()).isEqualTo(1);
    }

    @Test
    void theRefusals_matchTheirExamples_andSellNothing() throws Exception {
        sellActions(agentBearer(), "+263772000001", 1, null, "EARLY10")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_PROMO_CODE_NOT_ALLOWED, JsonCompareMode.STRICT));

        when(eventServiceClient.sellerAccess(any(), any(), any())).thenReturn(ApiResult.ok("down", null));
        sellActions(agentBearer(), "+263772000001", 1, null, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json(EXAMPLE_SELLER_UNCHECKED, JsonCompareMode.STRICT));

        mvc.perform(post("/bookings/box-office").header("Authorization", agentBearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263772000001",
                                 "paymentMethod":"CHEQUE","seats":[{"categoryId":"%s"}]}""".formatted(eventId, vip)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.paymentMethod").value("Must be one of: CASH, ONLINE."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void anOnlineSale_staysPending_carriesItsViewToken_andEarnsAtTheConfirm() throws Exception {
        MvcResult result = sell(agentBearer(), "+263772000001", 2, "ONLINE", null);
        String body = result.getResponse().getContentAsString();
        UUID booking = sold(result);
        assertThat((String) JsonPath.read(body, "$.message")).isEqualTo("Sale created, awaiting payment");
        assertThat((String) JsonPath.read(body, "$.data.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(body, "$.data.soldBy")).isEqualTo("Tendai Moyo");
        String viewToken = JsonPath.read(body, "$.data.viewToken");
        assertThat(viewToken).isEqualTo(jdbc.queryForObject("SELECT link_token FROM bookings WHERE id = ?",
                String.class, booking));
        assertThat(jdbc.queryForObject("SELECT agent_commission_amount FROM bookings WHERE id = ?",
                BigDecimal.class, booking)).isNull();

        mvc.perform(patch("/bookings/internal/" + booking + "/confirm").header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentRail\":\"ECOCASH\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT agent_commission_amount FROM bookings WHERE id = ?",
                BigDecimal.class, booking)).isEqualByComparingTo("1.00");
        mvc.perform(get("/bookings/box-office/my-sales").header("Authorization", agentBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)))
                .andExpect(jsonPath("$.data.entries[0].paymentMethod").value("ECOCASH"))
                .andExpect(jsonPath("$.data.totals.onlineSales").value(20.00))
                .andExpect(jsonPath("$.data.totals.cashTaken").value(0.00))
                .andExpect(jsonPath("$.data.totals.commissionEarned").value(1.00));
    }

    @Test
    void theAgentSeesOnlyTheirSales_theOrganizerSeesEveryAgent_andRecordsHandovers() throws Exception {
        UUID kept = sold(sell(agentBearer(), "+263772004567", 2, null, null));
        UUID voided = sold(sell(agentBearer(), "+263772000002", 1, null, null));
        sold(sell(organizerBearer(), "+263772000003", 1, null, null)); // the organizer's own: no agent's
        mvc.perform(post("/bookings/box-office/" + voided + "/void").header("Authorization", organizerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Buyer changed their mind\"}"))
                .andExpect(status().isOk());

        // Handovers: refused for an unknown agent and an event the agent did not sell for.
        mvc.perform(post("/event-organizer/reports/sales-agents/" + UUID.randomUUID() + "/handovers")
                        .header("Authorization", organizerBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_AGENT_NOT_FOUND, JsonCompareMode.STRICT));
        mvc.perform(post("/event-organizer/reports/sales-agents/" + agentRow + "/handovers")
                        .header("Authorization", organizerBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_HANDOVER_EVENT_INVALID, JsonCompareMode.STRICT));
        // The agent cannot record their own.
        mvc.perform(post("/event-organizer/reports/sales-agents/" + agentRow + "/handovers")
                        .header("Authorization", agentBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isForbidden());

        String recorded = mvc.perform(post("/event-organizer/reports/sales-agents/" + agentRow + "/handovers")
                        .header("Authorization", organizerBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5,\"eventId\":\"" + eventId + "\",\"note\":\"Midday cash\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(5.00))
                .andExpect(jsonPath("$.data.recordedBy").value("organizer@example.com"))
                .andReturn().getResponse().getContentAsString();
        String mistaken = mvc.perform(post("/event-organizer/reports/sales-agents/" + agentRow + "/handovers")
                        .header("Authorization", organizerBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID mistakenId = UUID.fromString(JsonPath.read(mistaken, "$.data.id"));
        mvc.perform(delete("/event-organizer/reports/sales-agents/" + agentRow + "/handovers/" + mistakenId)
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedBy").value("organizer@example.com"));
        mvc.perform(delete("/event-organizer/reports/sales-agents/" + agentRow + "/handovers/" + mistakenId)
                        .header("Authorization", bearer("other@example.com", List.of("EVENT_ORGANIZER"),
                                Map.of("organizerUuid", UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(content().json(EXAMPLE_HANDOVER_NOT_FOUND, JsonCompareMode.STRICT));
        mvc.perform(get("/event-organizer/reports/sales-agents/" + agentRow + "/handovers")
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        assertThat(recorded).contains("Midday cash");

        // The agent: their two sales, one voided, the live handover, the buyer's phone masked.
        mvc.perform(get("/bookings/box-office/my-sales").param("eventId", eventId.toString())
                        .header("Authorization", agentBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(2)))
                .andExpect(jsonPath("$.data.entries[?(@.status == 'VOIDED')].bookingId").value(voided.toString()))
                .andExpect(jsonPath("$.data.entries[?(@.bookingId == '" + kept + "')].customerPhone")
                        .value("****4567"))
                .andExpect(jsonPath("$.data.totals.ticketsSold").value(2))
                .andExpect(jsonPath("$.data.totals.cashTaken").value(30.00))
                .andExpect(jsonPath("$.data.totals.cashVoided").value(10.00))
                .andExpect(jsonPath("$.data.totals.commissionEarned").value(1.00))
                .andExpect(jsonPath("$.data.totals.handedOver").value(5.00))
                .andExpect(jsonPath("$.data.totals.cashOwed").value(15.00))
                .andExpect(jsonPath("$.data.handovers", hasSize(1)))
                .andExpect(jsonPath("$.data.handovers[0].recordedBy").doesNotExist());
        // Another account sees none of it.
        mvc.perform(get("/bookings/box-office/my-sales").header("Authorization", bearer(null, List.of("CUSTOMER"),
                        Map.of("userUuid", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(0)))
                .andExpect(jsonPath("$.data.handovers", hasSize(0)));

        // The organizer: one agent row; the organizer's own sale is not an agent's.
        mvc.perform(get("/event-organizer/reports/sales-agents").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agents", hasSize(1)))
                .andExpect(jsonPath("$.data.agents[0].agentId").value(agentRow.toString()))
                .andExpect(jsonPath("$.data.agents[0].displayName").value("Tendai Moyo"))
                .andExpect(jsonPath("$.data.agents[0].ticketsSold").value(2))
                .andExpect(jsonPath("$.data.agents[0].cashOwed").value(15.00))
                .andExpect(jsonPath("$.data.agents[0].commissionOwed").value(1.00))
                .andExpect(jsonPath("$.meta.platformWide").value(false));
        // Another organizer sees no agents of this one.
        mvc.perform(get("/event-organizer/reports/sales-agents").header("Authorization",
                        bearer("other@example.com", List.of("EVENT_ORGANIZER"),
                                Map.of("organizerUuid", UUID.randomUUID().toString()))))
                .andExpect(jsonPath("$.data.agents", hasSize(0)));

        // The cash-up: the agent's own till, under the agent's name, with the agent's void on it.
        mvc.perform(get("/event-organizer/reports/cash-up").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellers", hasSize(2)))
                .andExpect(jsonPath("$.data.sellers[?(@.seller == 'Tendai Moyo')].agentId").value(agentRow.toString()))
                .andExpect(jsonPath("$.data.sellers[?(@.seller == 'Tendai Moyo')].net").value(20.00))
                .andExpect(jsonPath("$.data.sellers[?(@.seller == 'organizer@example.com')].voids").value(0));
    }

    @Test
    void theMigration_addsTheColumnsTheIndexAndTheChecks() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'bookings' AND column_name IN
                  ('sold_by_agent_id', 'agent_commission_percent', 'agent_commission_amount')""", String.class))
                .containsExactlyInAnyOrder("sold_by_agent_id", "agent_commission_percent", "agent_commission_amount");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class,
                "idx_bookings_sold_by_agent_event"))
                .contains("(sold_by_agent_id, event_id)").contains("WHERE (sold_by_agent_id IS NOT NULL)");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_cash_handovers (id, organizer_uuid, agent_id, amount, currency, received_at, created_at)
                VALUES (?, ?, ?, 0, 'USD', now(), now())""", UUID.randomUUID(), organizer, agentRow))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_agent_cash_handovers_amount");
        UUID booking = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO bookings (id, version, user_email, event_id, confirmation_number, link_token, status,
                                      total_amount, availability_released, agent_commission_percent)
                VALUES (?, 0, 'it@example.com', ?, ?, ?, 'PENDING', 10.00, false, 100.01)""", booking, eventId,
                "INN-IT-" + booking.toString().substring(0, 8), booking.toString().replace("-", "")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_bookings_agent_commission_percent");
    }
}
