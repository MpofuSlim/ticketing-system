package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "The first 10 tickets at the early-bird price" is exact on real Postgres:
 * a burst of concurrent bookings sells exactly 10 at it and the rest at the
 * next tier, because each booking counts the tier only after locking the
 * category's stock row. A cancelled early-bird ticket frees its place.
 */
class BookingPriceTierPostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID earlyTier = UUID.randomUUID();
    private final UUID phaseTier = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    /** VIP 150.00: Early bird 100.00 for the first 10 tickets, then Phase 1 120.00. */
    private void tieredCategory() {
        category(eventId, categoryId, "VIP", 1_000);
        String inAWeek = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0) + "Z";
        when(seatServiceClient.getCategory(categoryId)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(categoryId).eventId(eventId).name("VIP").price(new BigDecimal("150.00"))
                .priceTiers(List.of(
                        CategoryLookupDTO.PriceTier.builder().id(earlyTier).name("Early bird")
                                .price(new BigDecimal("100.00")).endsAt(inAWeek).quantity(10).build(),
                        CategoryLookupDTO.PriceTier.builder().id(phaseTier).name("Phase 1")
                                .price(new BigDecimal("120.00")).endsAt(inAWeek).build()))
                .totalSeats(1_000).build()));
    }

    /** VIP 150.00: one {@code early} tier ahead of Phase 1 120.00. */
    private void tieredCategory(CategoryLookupDTO.PriceTier early) {
        category(eventId, categoryId, "VIP", 1_000);
        String inAWeek = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0) + "Z";
        when(seatServiceClient.getCategory(categoryId)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(categoryId).eventId(eventId).name("VIP").price(new BigDecimal("150.00"))
                .priceTiers(List.of(early,
                        CategoryLookupDTO.PriceTier.builder().id(phaseTier).name("Phase 1")
                                .price(new BigDecimal("120.00")).endsAt(inAWeek).build()))
                .totalSeats(1_000).build()));
    }

    private MvcResult book(int tickets) throws Exception {
        return book(tickets, "+263771234567");
    }

    private MvcResult book(int tickets, String phone) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int i = 0; i < tickets; i++) {
            seats.append(i == 0 ? "" : ",").append("{\"categoryId\":\"").append(categoryId).append("\"}");
        }
        return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"%s","seats":[%s]}
                """.formatted(eventId, phone, seats))).andReturn();
    }

    private long ticketsAt(String tierName) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.event_id = ? AND i.price_tier_name IS NOT DISTINCT FROM ?""", Long.class, eventId, tierName);
        return count == null ? 0 : count;
    }

    @Test
    void aBurstOfBookings_sellsExactlyTheTiersQuantity_atItsPrice() throws Exception {
        tieredCategory();
        int bookings = 60;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < bookings; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return book(1);
                }));
            }
            start.countDown();
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ticketsAt("Early bird")).isEqualTo(10);
        assertThat(ticketsAt("Phase 1")).isEqualTo(50);
        BigDecimal total = jdbc.queryForObject("SELECT sum(total_amount) FROM bookings WHERE event_id = ?",
                BigDecimal.class, eventId);
        assertThat(total).isEqualByComparingTo("7000.00"); // 10 × 100 + 50 × 120
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.event_id = ? AND i.price_tier_id = ? AND i.price_at_booking = 100.00""",
                Long.class, eventId, earlyTier)).isEqualTo(10);
    }

    @Test
    void aBookingStraddlingTheQuantity_isSplit_andACancelledTicketFreesItsPlace() throws Exception {
        tieredCategory();
        assertThat(book(8).getResponse().getStatus()).isEqualTo(201);

        MvcResult straddling = book(4);
        assertThat(straddling.getResponse().getStatus()).isEqualTo(201);
        assertThat(ticketsAt("Early bird")).isEqualTo(10);
        assertThat(ticketsAt("Phase 1")).isEqualTo(2);

        // The first booking's hold lapses: its 8 early-bird places are free again.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED' WHERE event_id = ? AND total_amount = 800.00",
                eventId);
        assertThat(book(1).getResponse().getContentAsString()).contains("\"priceTierName\":\"Early bird\"");

        mvc.perform(get("/bookings/internal/categories/tier-counts")
                        .param("categoryIds", categoryId.toString())
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.priceTierId == '" + earlyTier + "')].count").value(3))
                .andExpect(jsonPath("$.data[?(@.priceTierId == '" + phaseTier + "')].count").value(2));
    }

    @Test
    void thePerCustomerLimit_isCountedByPhone_acrossBookings() throws Exception {
        tieredCategory(CategoryLookupDTO.PriceTier.builder().id(earlyTier).name("Early bird")
                .price(new BigDecimal("100.00")).quantity(10).maxPerCustomer(2).build());

        assertThat(book(3).getResponse().getStatus()).isEqualTo(201);
        assertThat(book(1).getResponse().getContentAsString()).contains("\"priceTierName\":\"Phase 1\"");
        assertThat(book(1, "+263772222222").getResponse().getContentAsString())
                .contains("\"priceTierName\":\"Early bird\"");

        assertThat(ticketsAt("Early bird")).isEqualTo(3); // two for the first phone, one for the second
        assertThat(ticketsAt("Phase 1")).isEqualTo(2);
    }

    @Test
    void refundedTickets_keepTheirPlace_whenTheTierDoesNotReopen() throws Exception {
        tieredCategory(CategoryLookupDTO.PriceTier.builder().id(earlyTier).name("Early bird")
                .price(new BigDecimal("100.00")).quantity(10).refundsReopen(false).build());
        assertThat(book(8).getResponse().getStatus()).isEqualTo(201);
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'ADMIN_REVERSED' WHERE event_id = ?",
                eventId);

        assertThat(book(4).getResponse().getStatus()).isEqualTo(201);

        // 8 refunded still count: 2 places left at the early-bird price.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.event_id = ? AND b.status <> 'CANCELLED' AND i.price_tier_name = 'Early bird'""",
                Long.class, eventId)).isEqualTo(2);
        mvc.perform(get("/bookings/internal/categories/tier-counts")
                        .param("categoryIds", categoryId.toString())
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.priceTierId == '" + earlyTier + "')].count").value(2))
                .andExpect(jsonPath("$.data[?(@.priceTierId == '" + earlyTier + "')].refunded").value(8));
    }

    @Test
    void theTierCounts_needTheInternalToken() throws Exception {
        mvc.perform(get("/bookings/internal/categories/tier-counts").param("categoryIds", categoryId.toString()))
                .andExpect(status().isUnauthorized());
    }
}
