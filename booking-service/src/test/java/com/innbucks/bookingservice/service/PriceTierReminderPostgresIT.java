package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V28 on real Postgres: a customer follows an event from their own session,
 * and the job messages them once about the tier on sale, however many times it
 * runs (the claim is an INSERT ... ON CONFLICT DO NOTHING committed first).
 */
class PriceTierReminderPostgresIT extends BookingStockPostgresTestBase {

    private static final String PHONE = "+263771234599";

    @Autowired private PriceTierReminderService reminders;

    private final UUID eventId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID earlyId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM event_reminder_subscriptions WHERE event_id = ?", eventId);
    }

    private String customer() {
        return bearer(PHONE, List.of("CUSTOMER"), Map.of("phoneNumber", PHONE));
    }

    @Test
    void aFollowerHearsOnce_andCanStop() throws Exception {
        mvc.perform(post("/bookings/event-reminders").header("Authorization", customer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"%s\"}".formatted(eventId)))
                .andExpect(status().isCreated());
        mvc.perform(get("/bookings/event-reminders").header("Authorization", customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.eventId == '" + eventId + "')]").exists());

        String endsSoon = LocalDateTime.now(ZoneOffset.UTC).plusHours(6).withNano(0) + "Z";
        when(seatServiceClient.getCategoriesWithTiersEnding(anyString(), anyString(), any())).thenReturn(ApiResult.ok(
                List.of(CategoryLookupDTO.builder().seatCategoryId(categoryId).eventId(eventId).name("VIP")
                        .price(new BigDecimal("150.00")).priceTiers(List.of(CategoryLookupDTO.PriceTier.builder()
                                .id(earlyId).name("Early bird").price(new BigDecimal("100.00")).endsAt(endsSoon)
                                .quantity(10).build())).build())));
        when(eventServiceClient.getEvent(eventId)).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).title("Harare Jazz Festival").build()));

        reminders.remind();
        reminders.remind();

        verify(whatsApp, times(1)).sendCustomNotification(eq(PHONE), contains("Early bird tickets for VIP (100.00)"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM price_tier_reminders_sent s "
                + "JOIN event_reminder_subscriptions e ON e.id = s.subscription_id WHERE e.event_id = ?",
                Long.class, eventId)).isEqualTo(1);

        mvc.perform(delete("/bookings/event-reminders/{eventId}", eventId).header("Authorization", customer()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT unsubscribed_at IS NOT NULL FROM event_reminder_subscriptions "
                + "WHERE event_id = ?", Boolean.class, eventId)).isTrue();
    }

    @Test
    void onlyACustomerSession_canFollow() throws Exception {
        mvc.perform(post("/bookings/event-reminders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\"}".formatted(eventId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings/event-reminders")
                        .header("Authorization", bearer("organizer@example.com", List.of("EVENT_ORGANIZER"), Map.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"%s\"}".formatted(eventId)))
                .andExpect(status().isForbidden());
    }
}
