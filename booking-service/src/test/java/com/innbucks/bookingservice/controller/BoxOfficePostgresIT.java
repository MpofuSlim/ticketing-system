package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_ALREADY_VOIDED;
import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_EVENT_STARTED;
import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_KEY_REQUIRED;
import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_NOT_A_CASH_SALE;
import static com.innbucks.bookingservice.controller.BoxOfficeController.EXAMPLE_TICKET_SCANNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The box office (V35) through the real filter chain and Postgres: a cash sale
 * is an online sale confirmed at once and stamped with its seller, a retry with
 * the same Idempotency-Key never sells twice, only the event's organizer sells
 * or voids, a void puts the stock back and is refused once a ticket is scanned
 * or the event has started, a cancelled ticket can never be claimed at the
 * gate, and the cash-up and revenue reports carry it all. The documented
 * refusals are compared strictly with their Swagger examples.
 */
class BoxOfficePostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, vip, "VIP", 100);
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer).build()));
        eventStartsIn(30);
        // A void hands its tickets back to event-service's availability mirror
        // first, like the admin reversal, and refuses (503) when that is not
        // acknowledged.
        AvailabilityResponseDTO released = new AvailabilityResponseDTO();
        released.setAvailableTickets(98);
        when(eventServiceClient.releaseAvailability(eq(eventId), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM scan_attempts WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    private void eventStartsIn(int days) {
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).title("Jazz Night")
                        .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(days)).build()));
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString(), "userUuid", organizer.toString()));
    }

    private ResultActions sell(String bearer, String key, int tickets) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int k = 0; k < tickets; k++) {
            seats.append(k == 0 ? "" : ",").append("{\"categoryId\":\"").append(vip).append("\"}");
        }
        var request = post("/bookings/box-office").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"0771234567",
                         "userEmail":"rufaro@example.com","seats":[%s]}
                        """.formatted(eventId, seats));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private UUID sold(int tickets) throws Exception {
        String body = sell(organizerBearer(), UUID.randomUUID().toString(), tickets)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private ResultActions voidSale(UUID bookingId, String bearer) throws Exception {
        return mvc.perform(post("/bookings/box-office/" + bookingId + "/void").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Buyer changed their mind\"}"));
    }

    @Test
    void aCashSale_isConfirmedAtOnce_asTheSellers_andTheTicketsGoOutAsOnline() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = sell(organizerBearer(), key, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Cash sale recorded"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+263771234567"))
                .andExpect(jsonPath("$.data.userEmail").value("rufaro@example.com"))
                .andExpect(jsonPath("$.data.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.data.paymentMethodLabel").value("Cash (box office)"))
                .andExpect(jsonPath("$.data.soldBy").value("organizer@example.com"))
                .andExpect(jsonPath("$.data.cashAmount").value(20.00))
                .andReturn().getResponse().getContentAsString();
        UUID booking = UUID.fromString(JsonPath.read(first, "$.data.id"));
        assertThat(jdbc.queryForObject("SELECT sold_by_user_uuid FROM bookings WHERE id = ?", UUID.class, booking))
                .isEqualTo(organizer);
        assertThat(remaining(vip)).isEqualTo(98);
        verify(whatsApp, timeout(10_000).atLeastOnce()).sendEventQrCode(eq("+263771234567"), anyString(), anyString());

        // A double click: the same key answers the first sale, never a second one.
        sell(organizerBearer(), key, 2).andExpect(jsonPath("$.data.id").value(booking.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);

        // The organizer sees the seller on the guest list; the revenue splits out cash.
        mvc.perform(get("/bookings/by-event/" + eventId).header("Authorization", organizerBearer()))
                .andExpect(jsonPath("$.data[0].soldBy").value("organizer@example.com"));
        mvc.perform(get("/event-organizer/reports/revenue").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byPaymentMethod[0].method").value("CASH"))
                .andExpect(jsonPath("$.data.byPaymentMethod[0].label").value("Cash (box office)"));
    }

    @Test
    void onlyTheEventsOwnOrganizerSells_andAKeyIsRequired() throws Exception {
        sell(organizerBearer(), null, 1)
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_KEY_REQUIRED, JsonCompareMode.STRICT));
        sell(bearer("other@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", UUID.randomUUID().toString())), UUID.randomUUID().toString(), 1)
                .andExpect(status().isForbidden());
        sell(bearer("buyer@example.com", List.of("CUSTOMER"), Map.of("phoneNumber", "+263771234567")),
                UUID.randomUUID().toString(), 1)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void aVoid_putsTheStockBack_once_andShowsInTheCashUp() throws Exception {
        UUID kept = sold(2);
        UUID voided = sold(1);
        assertThat(remaining(vip)).isEqualTo(97);

        voidSale(voided, bearer("other@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        voidSale(voided, organizerBearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.voidedBy").value("organizer@example.com"))
                .andExpect(jsonPath("$.data.voidReason").value("Buyer changed their mind"));
        assertThat(remaining(vip)).isEqualTo(98);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM bookings WHERE id = ?", String.class, voided))
                .isEqualTo("CASH_VOIDED");
        voidSale(voided, organizerBearer())
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_ALREADY_VOIDED, JsonCompareMode.STRICT));
        // Released once, for the voided sale's one ticket: neither the
        // stranger's attempt nor the repeat reached event-service.
        verify(eventServiceClient, times(1)).releaseAvailability(eq(eventId), anyInt(), anyString());
        verify(eventServiceClient).releaseAvailability(eq(eventId), eq(1), anyString());

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mvc.perform(get("/event-organizer/reports/cash-up").param("eventId", eventId.toString())
                        .param("from", today).param("to", today).header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellers", hasSize(1)))
                .andExpect(jsonPath("$.data.totals.sales").value(2))
                .andExpect(jsonPath("$.data.totals.tickets").value(3))
                .andExpect(jsonPath("$.data.totals.taken").value(30.00))
                .andExpect(jsonPath("$.data.totals.voids").value(1))
                .andExpect(jsonPath("$.data.totals.returned").value(10.00))
                .andExpect(jsonPath("$.data.totals.net").value(20.00))
                .andExpect(jsonPath("$.data.entries", hasSize(3)))
                .andExpect(jsonPath("$.data.entries[2].type").value("VOID"))
                .andExpect(jsonPath("$.meta.eventId").value(eventId.toString()));
        assertThat(kept).isNotNull();
    }

    @Test
    void aVoid_isRefusedOnceATicketIsScanned_orTheEventHasStarted_orForAnOnlineSale() throws Exception {
        UUID scanned = sold(1);
        String ticket = jdbc.queryForObject("SELECT ticket_number FROM booking_items WHERE booking_id = ?",
                String.class, scanned);
        mvc.perform(post("/tickets/scan").header("Authorization", organizerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketNumber\":\"" + ticket + "\"}"))
                .andExpect(jsonPath("$.data.status").value("ALLOWED"));
        voidSale(scanned, organizerBearer())
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_TICKET_SCANNED, JsonCompareMode.STRICT));

        UUID late = sold(1);
        eventStartsIn(-1);
        voidSale(late, organizerBearer())
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_EVENT_STARTED, JsonCompareMode.STRICT));
        eventStartsIn(30);

        String online = mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Tendai Ncube","phoneNumber":"+263772000111",
                         "seats":[{"categoryId":"%s"}]}
                        """.formatted(eventId, vip)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID onlineId = UUID.fromString(JsonPath.read(online, "$.data.id"));
        mvc.perform(patch("/bookings/internal/" + onlineId + "/confirm").header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentRail\":\"CASH\"}"))
                .andExpect(status().isOk());
        // payment-service can never make a booking look like a cash sale.
        assertThat(jdbc.queryForObject("SELECT payment_rail FROM bookings WHERE id = ?", String.class, onlineId))
                .isNull();
        voidSale(onlineId, organizerBearer())
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_NOT_A_CASH_SALE, JsonCompareMode.STRICT));
    }

    @Test
    void aCancelledBookingsTicket_canNeverBeClaimed_soAScanRacingAVoidAdmitsNobody() throws Exception {
        UUID booking = sold(1);
        UUID item = jdbc.queryForObject("SELECT id FROM booking_items WHERE booking_id = ?", UUID.class, booking);
        // The cancellation commits after a scan has read the booking as CONFIRMED.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'CASH_VOIDED' WHERE id = ?", booking);

        Integer claimed = new TransactionTemplate(txManager).execute(s -> bookingItemRepository.claimRedemption(
                item, LocalDateTime.now(ZoneOffset.UTC), organizer, "Gate 1"));
        assertThat(claimed).isZero();
        assertThat(jdbc.queryForObject("SELECT redeemed_at FROM booking_items WHERE id = ?", LocalDateTime.class,
                item)).isNull();
    }
}
