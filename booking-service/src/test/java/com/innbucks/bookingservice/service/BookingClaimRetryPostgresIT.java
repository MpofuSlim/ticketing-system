package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /bookings} when Postgres aborts the claim with a deadlock, end
 * to end: the 40P01 is a real one, raised by a trigger on the counter row
 * through the real JDBC driver, Hibernate and Spring's exception translation,
 * so the retry is proven to recognise what production would actually throw.
 * The trigger counts its firings on a sequence, which a rollback does not
 * undo, so "fail the first N claims" survives each attempt's rollback.
 */
class BookingClaimRetryPostgresIT extends BookingStockPostgresTestBase {

    private final String name = "it_force_40p01_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID eventId = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON category_inventory");
        jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
        jdbc.execute("DROP SEQUENCE IF EXISTS " + name);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    @Test
    void aDeadlockOnTheFirstAttempt_isRetried_andTheBuyerGets201() throws Exception {
        category(eventId, category, "Bay 12", 10);
        abortTheFirstClaims(1);
        double before = claimRetries("succeeded");

        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body(category)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items.length()").value(2));

        // The rolled-back first attempt left nothing behind: one booking, two
        // tickets, two seats claimed once.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                 WHERE b.event_id = ?""", Long.class, eventId)).isEqualTo(2);
        assertThat(remaining(category)).isEqualTo(8);
        assertThat(claimRetries("succeeded") - before).isEqualTo(1);
    }

    @Test
    void theSameFailureTwice_is503TryAgain_andNothingIsKept() throws Exception {
        category(eventId, category, "Bay 12", 10);
        abortTheFirstClaims(2);
        double before = claimRetries("failed");

        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body(category)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't reserve your tickets just now. Please try again."))
                .andExpect(jsonPath("$.data.errorCode").value("TRY_AGAIN"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isZero();
        assertThat(remaining(category)).isEqualTo(10);
        assertThat(claimRetries("failed") - before).isEqualTo(1);
    }

    @Test
    void aMalformedCategoryId_is400_throughTheWholeStack() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                         "seats":[{"categoryId":"c-vip"}]}
                        """.formatted(eventId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data['seats[0].categoryId']").value("Must be a valid UUID."));
    }

    private void abortTheFirstClaims(int times) {
        jdbc.execute("CREATE SEQUENCE " + name);
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF nextval('" + name + "') <= " + times + " THEN "
                + "RAISE EXCEPTION 'deadlock detected (forced by the test)' USING ERRCODE = '40P01'; "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE UPDATE ON category_inventory FOR EACH ROW "
                + "WHEN (OLD.category_id = '" + category + "') EXECUTE FUNCTION " + name + "()");
    }

    private String body(UUID categoryId) {
        return """
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                 "seats":[{"categoryId":"%s"},{"categoryId":"%s"}]}
                """.formatted(eventId, categoryId, categoryId);
    }
}
