package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promo codes (V27) on real Postgres, through the real filter chain: the
 * organizer creates and edits one, anyone checks it, and a burst of
 * concurrent bookings uses it exactly as many times as it allows, because
 * each booking counts its uses under the code's row lock.
 */
class BookingPromoCodePostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, categoryId, "VIP", 1_000);
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(owner).build()));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM promo_codes WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    private String organizer(UUID organizerUuid) {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizerUuid.toString()));
    }

    private MvcResult createCode(String bearer, String body) throws Exception {
        return mvc.perform(post("/bookings/promo-codes").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String codeBody(int maxUses) {
        String ends = LocalDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0) + "Z";
        return """
                {"eventId":"%s","code":"early10","discountType":"PERCENT","discountValue":10,
                 "maxUses":%d,"maxUsesPerCustomer":1,"endsAt":"%s"}
                """.formatted(eventId, maxUses, ends);
    }

    private MvcResult book(String phone, String code) throws Exception {
        return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"%s","promoCode":"%s",
                 "seats":[{"categoryId":"%s"}]}
                """.formatted(eventId, phone, code, categoryId))).andReturn();
    }

    @Test
    void aBurstOfBookings_usesTheCodeExactlyAsManyTimesAsItAllows() throws Exception {
        assertThat(createCode(organizer(owner), codeBody(5)).getResponse().getStatus()).isEqualTo(201);

        int bookings = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < bookings; i++) {
                String phone = "+26377100%04d".formatted(i);
                results.add(pool.submit(() -> {
                    start.await();
                    return book(phone, "EARLY10");
                }));
            }
            start.countDown();
            int created = 0;
            int usedUp = 0;
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                int code = r.getResponse().getStatus();
                if (code == 201) {
                    created++;
                } else {
                    assertThat(code).as(r.getResponse().getContentAsString()).isEqualTo(409);
                    assertThat(r.getResponse().getContentAsString()).contains("PROMO_CODE_USED_UP");
                    usedUp++;
                }
            }
            assertThat(created).isEqualTo(5);
            assertThat(usedUp).isEqualTo(15);
        } finally {
            pool.shutdownNow();
        }

        // 10.00 less 10% each, recorded on the booking and the ticket.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE event_id = ? AND promo_code = 'EARLY10' AND total_amount = 9.00 "
                        + "AND discount_amount = 1.00", Long.class, eventId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.event_id = ? AND i.price_at_booking = 9.00 AND i.discount_amount = 1.00""",
                Long.class, eventId)).isEqualTo(5);
        mvc.perform(get("/bookings/promo-codes").param("eventId", eventId.toString())
                        .header("Authorization", organizer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].uses").value(5))
                .andExpect(jsonPath("$.data[0].discountGiven").value(5.0));
        mvc.perform(post("/bookings/promo-codes/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"code\":\"early10\"}".formatted(eventId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("PROMO_CODE_USED_UP"));
    }

    @Test
    void aCancelledBooking_freesItsUse_andTheBuyersOwnLimitHolds() throws Exception {
        createCode(organizer(owner), codeBody(1));
        assertThat(book("+263771000001", "EARLY10").getResponse().getStatus()).isEqualTo(201);
        assertThat(book("+263771000002", "EARLY10").getResponse().getContentAsString())
                .contains("PROMO_CODE_USED_UP");

        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'EXPIRED' WHERE event_id = ?", eventId);

        assertThat(book("+263771000002", "EARLY10").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void theOrganizerEditsTheCode_andAnyoneCanCheckIt() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(
                createCode(organizer(owner), codeBody(5)).getResponse().getContentAsString(), "$.data.id");

        mvc.perform(post("/bookings/promo-codes/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"code\":\"Early10\"}".formatted(eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("EARLY10"))
                .andExpect(jsonPath("$.data.discountValue").value(10.0));

        mvc.perform(put("/bookings/promo-codes/{id}", id).header("Authorization", organizer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountType\":\"FIXED\",\"discountValue\":2.5,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discountType").value("FIXED"))
                .andExpect(jsonPath("$.data.active").value(false));

        mvc.perform(post("/bookings/promo-codes/check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"code\":\"EARLY10\"}".formatted(eventId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("PROMO_CODE_INVALID"));
    }

    @Test
    void onlyTheEventsOrganizerManagesItsCodes() throws Exception {
        assertThat(createCode(organizer(UUID.randomUUID()), codeBody(5)).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/bookings/promo-codes").contentType(MediaType.APPLICATION_JSON)
                .content(codeBody(5))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(createCode(organizer(owner), codeBody(5)).getResponse().getStatus()).isEqualTo(201);
        assertThat(createCode(organizer(owner), codeBody(5)).getResponse().getContentAsString())
                .contains("PROMO_CODE_EXISTS");
    }

    @Test
    void theDatabaseRefusesACodeThatWouldMakeTicketsFree() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO promo_codes (id, event_id, code, discount_type, discount_value, active, created_at)
                VALUES (?, ?, 'FREE', 'PERCENT', 100, TRUE, now())""", UUID.randomUUID(), eventId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_promo_codes_value");
    }
}
