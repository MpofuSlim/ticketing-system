package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Assigned numbers (V34) through the real filter chain and Postgres: numbers
 * are given at payment in the order the buyer listed the tickets, never
 * reused, exact under concurrent confirms, locked after the first, given to
 * tickets already sold on request, and shown on the guest list and the scan.
 */
class BookingNumberingPostgresIT extends BookingStockPostgresTestBase {

    @Autowired private BookingService bookingService;

    private final UUID eventId = UUID.randomUUID();
    private final UUID tenK = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, tenK, "10 km", 100);
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer).build()));
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).title("City 10K").build()));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM category_number_series WHERE category_id = ?", tenK);
        jdbc.update("DELETE FROM scan_attempts WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString()));
    }

    private ResultActions setNumbering(String body) throws Exception {
        return mvc.perform(put("/bookings/numbering/" + tenK).header("Authorization", organizerBearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID book(int tickets) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int k = 0; k < tickets; k++) {
            seats.append(k == 0 ? "" : ",").append("{\"categoryId\":\"").append(tenK).append("\"}");
        }
        String created = mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567","seats":[%s]}
                        """.formatted(eventId, seats)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].assignedNumber").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private ResultActions confirm(UUID bookingId) throws Exception {
        return mvc.perform(patch("/bookings/internal/" + bookingId + "/confirm")
                .header("X-Internal-Token", internalToken));
    }

    private List<String> numbersOf(UUID bookingId) {
        return jdbc.queryForList("SELECT assigned_number FROM booking_items WHERE booking_id = ? ORDER BY seat_number",
                String.class, bookingId);
    }

    @Test
    void numbersAreGivenAtPayment_inTicketOrder_neverReused_andTheSeriesLocksAfterTheFirst() throws Exception {
        setNumbering("{\"label\":\"Bib\",\"prefix\":\"T\",\"start\":1,\"padding\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.example").value("T001"))
                .andExpect(jsonPath("$.data.locked").value(false));
        // Still editable: nothing given yet.
        setNumbering("{\"label\":\"Bib\",\"prefix\":\"T\",\"start\":1,\"padding\":3}").andExpect(status().isOk());

        UUID first = book(2);
        UUID second = book(1);
        assertThat(numbersOf(first)).containsOnlyNulls();

        // Paid first, numbered first: a hold uses no number.
        confirm(second).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].assignedNumber").value("T001"))
                .andExpect(jsonPath("$.data.items[0].numberLabel").value("Bib"));
        confirm(first).andExpect(status().isOk());
        assertThat(numbersOf(first)).containsExactly("T002", "T003");
        // A replayed confirm gives nothing new.
        confirm(first).andExpect(status().isOk());
        assertThat(numbersOf(first)).containsExactly("T002", "T003");

        setNumbering("{\"prefix\":\"R\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("NUMBERING_IN_USE"))
                .andExpect(jsonPath("$.data.assigned").value(3));
        mvc.perform(delete("/bookings/numbering/" + tenK).header("Authorization", organizerBearer()))
                .andExpect(status().isConflict());

        // Refunded: the number stays on its ticket and nobody else gets it.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'ADMIN_REVERSED' WHERE id = ?", second);
        UUID third = book(1);
        confirm(third).andExpect(status().isOk());
        assertThat(numbersOf(second)).containsExactly("T001");
        assertThat(numbersOf(third)).containsExactly("T004");

        mvc.perform(get("/bookings/numbering").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].assigned").value(4))
                .andExpect(jsonPath("$.data[0].nextNumber").value("T005"))
                .andExpect(jsonPath("$.data[0].locked").value(true));

        // The guest list finds a number in any case, and the gate sees it.
        mvc.perform(get("/bookings/by-event/" + eventId).param("assignedNumber", "t003")
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].assignedNumber").value("T003"))
                .andExpect(jsonPath("$.data[0].numberLabel").value("Bib"));
        String ticket = jdbc.queryForObject("SELECT ticket_number FROM booking_items WHERE booking_id = ?",
                String.class, third);
        mvc.perform(post("/tickets/scan").header("Authorization", organizerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketNumber\":\"" + ticket + "\"}"))
                .andExpect(jsonPath("$.data.status").value("ALLOWED"))
                .andExpect(jsonPath("$.data.assignedNumber").value("T004"))
                .andExpect(jsonPath("$.data.numberLabel").value("Bib"));
    }

    @Test
    void ticketsSoldBeforeNumberingExisted_areNumberedOnRequest_inBookingOrder_once() throws Exception {
        UUID early = book(2);
        UUID later = book(1);
        UUID unpaid = book(1);
        confirm(early).andExpect(status().isOk());
        confirm(later).andExpect(status().isOk());
        assertThat(numbersOf(early)).containsOnlyNulls();

        setNumbering("{\"label\":\"Bib\",\"start\":100}").andExpect(status().isOk());
        mvc.perform(post("/bookings/numbering/" + tenK + "/assign-sold").header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Numbers given to 3 tickets"))
                .andExpect(jsonPath("$.data.numberedNow").value(3))
                .andExpect(jsonPath("$.data.numbering.nextNumber").value("103"));
        assertThat(numbersOf(early)).containsExactly("100", "101");
        assertThat(numbersOf(later)).containsExactly("102");
        assertThat(numbersOf(unpaid)).containsOnlyNulls();

        mvc.perform(post("/bookings/numbering/" + tenK + "/assign-sold").header("Authorization", organizerBearer()))
                .andExpect(jsonPath("$.data.numberedNow").value(0));
        confirm(unpaid).andExpect(status().isOk());
        assertThat(numbersOf(unpaid)).containsExactly("103");
    }

    @Test
    void concurrentConfirms_giveEveryTicketItsOwnNumber_withNoGap() throws Exception {
        setNumbering("{\"label\":\"Bib\"}").andExpect(status().isOk());
        List<UUID> bookings = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            bookings.add(book(1));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> confirms = new ArrayList<>();
            for (UUID id : bookings) {
                confirms.add(() -> bookingService.confirmBooking(id));
            }
            for (Future<Object> f : pool.invokeAll(confirms)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        List<Integer> numbers = jdbc.queryForList("SELECT CAST(assigned_number AS INTEGER) FROM booking_items i "
                + "JOIN bookings b ON b.id = i.booking_id WHERE b.event_id = ? ORDER BY 1", Integer.class, eventId);
        List<Integer> expected = new ArrayList<>();
        for (int k = 1; k <= 20; k++) {
            expected.add(k);
        }
        assertThat(numbers).isEqualTo(expected);
    }

    @Test
    void theDatabaseRefusesTheSameNumberTwiceInACategory_andABadSeries() throws Exception {
        setNumbering("{\"label\":\"Bib\"}").andExpect(status().isOk());
        UUID booking = book(2);
        confirm(booking).andExpect(status().isOk());
        UUID second = jdbc.queryForObject("SELECT id FROM booking_items WHERE booking_id = ? AND seat_number = 2",
                UUID.class, booking);
        assertThatThrownBy(() -> jdbc.update("UPDATE booking_items SET assigned_number = '1' WHERE id = ?", second))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE category_number_series SET prefix = 'T 1' WHERE category_id = ?",
                tenK)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBadFieldIsNamed_andSomeoneElsesEventIsRefused() throws Exception {
        setNumbering("{\"prefix\":\"T 1\",\"padding\":12}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.prefix").value("Letters, digits and hyphens only, at most 10."))
                .andExpect(jsonPath("$.data.padding").value("Must be between 0 and 9."));
        mvc.perform(put("/bookings/numbering/" + tenK)
                        .header("Authorization", bearer("other@example.com", List.of("EVENT_ORGANIZER"),
                                Map.of("organizerUuid", UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_number_series WHERE category_id = ?",
                Integer.class, tenK)).isZero();
    }
}
