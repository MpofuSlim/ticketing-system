package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.innbucks.common.ticket.TicketNumbers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /bookings} when a generated reference is already stored, end to
 * end: the clash is a REAL 23505 — the colliding ticket or confirmation
 * number is committed first, and the scripted generator hands the claim that
 * same value — so the retry is proven to recognise what Postgres, pgjdbc's
 * batch wrapping and Hibernate's translation actually throw. Each attempt's
 * stock claim must roll back with it, and the clashing value — a live entry
 * credential, for a ticket — must never reach the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class BookingReferenceCollisionPostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();

    @Autowired private BookingService bookingService;
    @Autowired private BookingReferences realReferences;

    private ScriptedReferences scripted;

    @BeforeEach
    void scriptTheGenerator() {
        scripted = new ScriptedReferences(realReferences);
        bookingService.setReferences(scripted);
    }

    @AfterEach
    void tearDown() {
        bookingService.setReferences(realReferences);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    @Test
    void aTicketNumberClash_isRetried_theBuyerGets201_andTheStockIsClaimedOnce(CapturedOutput output)
            throws Exception {
        category(eventId, category, "Bay 12", 10);
        Booking existing = seedBooking(Booking.BookingStatus.CONFIRMED, null, UUID.randomUUID());
        String taken = realReferences.nextTicketNumber();
        jdbc.update("UPDATE booking_items SET ticket_number = ? WHERE booking_id = ?", taken, existing.getId());
        scripted.tickets.add(taken);
        double before = referenceCollisions("ticket");
        double retriesBefore = allClaimRetries();

        String body = mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(scripted.tickets).isEmpty();
        List<String> issued = JsonPath.read(body, "$.data.items[*].ticketNumber");
        assertThat(issued).hasSize(2).doesNotContain(taken).allMatch(TicketNumbers::isCurrent);
        assertThat(jdbc.queryForList("""
                SELECT i.ticket_number FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                 WHERE b.event_id = ?""", String.class, eventId)).containsExactlyInAnyOrderElementsOf(issued);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        // 10 - 2, not 10 - 4: the clashing attempt's claim rolled back with it.
        assertThat(remaining(category)).isEqualTo(8);
        // The ticket that was there first is untouched.
        assertThat(jdbc.queryForObject("SELECT booking_id FROM booking_items WHERE ticket_number = ?",
                UUID.class, taken)).isEqualTo(existing.getId());
        assertThat(referenceCollisions("ticket") - before).isEqualTo(1);
        assertThat(allClaimRetries()).isEqualTo(retriesBefore);
        // The driver's error reached the log (so this is not vacuous) without
        // the bound values or the "Detail: Key (ticket_number)=(...)" line.
        assertThat(output).contains("uk_booking_items_ticket_number")
                .contains("Claim hit a ticket number collision")
                .doesNotContain(taken);
    }

    @Test
    void aConfirmationNumberClash_isRetried_withAFreshNumber(CapturedOutput output) throws Exception {
        category(eventId, category, "Bay 12", 10);
        Booking existing = seedBooking(Booking.BookingStatus.CONFIRMED, null, UUID.randomUUID());
        String taken = unusedConfirmationNumber();
        jdbc.update("UPDATE bookings SET confirmation_number = ? WHERE id = ?", taken, existing.getId());
        scripted.confirmations.add(taken);
        double before = referenceCollisions("confirmation");

        String body = mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body(2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(scripted.confirmations).isEmpty();
        String confirmation = JsonPath.read(body, "$.data.confirmationNumber");
        assertThat(confirmation).isNotEqualTo(taken).matches("INN-\\d{8}-[0-9A-F]{6}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(remaining(category)).isEqualTo(8);
        assertThat(referenceCollisions("confirmation") - before).isEqualTo(1);
        assertThat(output).contains("uk_bookings_confirmation_number").doesNotContain(taken);
    }

    @Test
    void aClashOnEveryAttempt_is503TryAgain_andNothingIsHeld() throws Exception {
        category(eventId, category, "Bay 12", 10);
        Booking existing = seedBooking(Booking.BookingStatus.CONFIRMED, null, UUID.randomUUID());
        String taken = realReferences.nextTicketNumber();
        jdbc.update("UPDATE booking_items SET ticket_number = ? WHERE booking_id = ?", taken, existing.getId());
        for (int i = 0; i <= BookingService.MAX_COLLISION_RETRIES; i++) {
            scripted.tickets.add(taken);
        }
        double before = referenceCollisions("ticket");

        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body(1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't reserve your tickets just now. Please try again."))
                .andExpect(jsonPath("$.data.errorCode").value("TRY_AGAIN"));

        assertThat(scripted.tickets).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isZero();
        assertThat(remaining(category)).isEqualTo(10);
        assertThat(referenceCollisions("ticket") - before).isEqualTo(BookingService.MAX_COLLISION_RETRIES + 1);
    }

    // ---- helpers -------------------------------------------------------------

    private String unusedConfirmationNumber() {
        while (true) {
            String candidate = realReferences.nextConfirmationNumber();
            Long taken = jdbc.queryForObject("SELECT count(*) FROM bookings WHERE confirmation_number = ?",
                    Long.class, candidate);
            if (taken != null && taken == 0) {
                return candidate;
            }
        }
    }

    private double allClaimRetries() {
        return claimRetries("succeeded") + claimRetries("failed") + claimRetries("refused");
    }

    private String body(int seats) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < seats; i++) {
            items.append(i == 0 ? "" : ",").append("{\"categoryId\":\"").append(category).append("\"}");
        }
        return """
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567","seats":[%s]}
                """.formatted(eventId, items);
    }

    /** Hands out the queued values first, then real ones. */
    static final class ScriptedReferences extends BookingReferences {
        final Deque<String> tickets = new ArrayDeque<>();
        final Deque<String> confirmations = new ArrayDeque<>();
        private final BookingReferences real;

        ScriptedReferences(BookingReferences real) {
            this.real = real;
        }

        @Override
        public String nextTicketNumber() {
            String next = tickets.poll();
            return next != null ? next : real.nextTicketNumber();
        }

        @Override
        public String nextConfirmationNumber() {
            String next = confirmations.poll();
            return next != null ? next : real.nextConfirmationNumber();
        }
    }
}
