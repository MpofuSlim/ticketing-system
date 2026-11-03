package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.answers.BookingAnswersRetentionJob;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingQuestionDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_ANSWERS_INVALID;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_TERMS_CHANGED;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_TERMS_NOT_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Terms and booking answers (V32) through the real filter chain, the app's own
 * JSON mapper and Postgres: what the buyer sends, what lands encrypted in the
 * row, what the organizer reads back, the report, the 90-day deletion, and the
 * Swagger examples of the three refusals compared strictly.
 */
class BookingTermsAndAnswersPostgresIT extends BookingStockPostgresTestBase {

    private static final UUID CONTACT = UUID.fromString("7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e02");
    private static final UUID BIRTH = UUID.fromString("7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e03");
    private static final UUID SIZE = UUID.fromString("7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e01");
    private static final LocalDateTime END = LocalDateTime.of(2030, 4, 18, 10, 0);

    @Autowired private BookingAnswersRetentionJob retentionJob;

    private final UUID eventId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, categoryId, "21 km", 100);
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).build()));
        eventAsks(2);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    private void eventAsks(Integer termsVersion) {
        List<BookingQuestionDTO> questions = List.of(
                BookingQuestionDTO.builder().id(SIZE).label("T-shirt size").type("CHOICE").required(true)
                        .options(List.of("S", "M", "L")).categoryIds(List.of()).build(),
                BookingQuestionDTO.builder().id(CONTACT).label("Emergency contact (name and phone)").type("TEXT")
                        .required(true).maxLength(120).options(List.of()).categoryIds(List.of()).build(),
                BookingQuestionDTO.builder().id(BIRTH).label("Date of birth").type("DATE").required(true)
                        .options(List.of()).categoryIds(List.of(categoryId)).build());
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .endDateTime(END).tenantUserUuid(organizer).termsVersion(termsVersion)
                        .bookingQuestions(questions).build()));
    }

    private ResultActions book(String acceptedTerms, String seats) throws Exception {
        return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567"%s,
                 "seats":%s}
                """.formatted(eventId, acceptedTerms == null ? "" : ",\"acceptedTermsVersion\":" + acceptedTerms,
                seats)));
    }

    private String goodSeat() {
        return """
                [{"categoryId":"%s","answers":[
                   {"questionId":"%s","value":"m"},
                   {"questionId":"%s","value":"Chipo Ncube +263772000111"},
                   {"questionId":"%s","value":"1994-03-18"}]}]
                """.formatted(categoryId, SIZE, CONTACT, BIRTH);
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString()));
    }

    @Test
    void aBooking_carriesItsAnswers_encryptedAtRest_andTheOrganizerReadsThemBack() throws Exception {
        book("2", goodSeat())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.termsVersion").value(2))
                .andExpect(jsonPath("$.data.items[0].answers", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].answers[0].value").value("M"))
                .andExpect(jsonPath("$.data.items[0].answers[2].label").value("Date of birth"));

        String stored = jdbc.queryForObject("SELECT i.answers FROM booking_items i JOIN bookings b "
                + "ON b.id = i.booking_id WHERE b.event_id = ?", String.class, eventId);
        assertThat(stored).startsWith("v1:").doesNotContain("Chipo");
        assertThat(jdbc.queryForObject("SELECT answers_purge_after FROM bookings WHERE event_id = ?",
                LocalDateTime.class, eventId)).isEqualTo(END.plusDays(90));

        jdbc.update("UPDATE bookings SET status = 'CONFIRMED' WHERE event_id = ?", eventId);
        mvc.perform(get("/bookings/by-event/" + eventId).header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].termsVersion").value(2))
                .andExpect(jsonPath("$.data[0].answers[1].value").value("Chipo Ncube +263772000111"));

        mvc.perform(get("/event-organizer/reports/booking-questions").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedTickets").value(1))
                .andExpect(jsonPath("$.data.questions[0].options[0].value").value("M"))
                .andExpect(jsonPath("$.data.questions[1].answered").value(1))
                .andExpect(jsonPath("$.data.termsAccepted[0].version").value(2))
                .andExpect(jsonPath("$.meta.eventId").value(eventId.toString()));

        // 90 days after the event's (live) end, the answers go; the terms record stays.
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("ok",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ENDED)
                        .endDateTime(END).build()));
        assertThat(retentionJob.purgeDue(END.plusDays(91))).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT i.answers FROM booking_items i JOIN bookings b "
                + "ON b.id = i.booking_id WHERE b.event_id = ?", String.class, eventId)).isNull();
        mvc.perform(get("/event-organizer/reports/booking-questions").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(jsonPath("$.data.answersDeleted").value(true))
                .andExpect(jsonPath("$.data.questions", hasSize(0)))
                .andExpect(jsonPath("$.data.termsAccepted[0].bookings").value(1));
    }

    @Test
    void theRefusals_areTheDocumentedBodies_andHoldNothing() throws Exception {
        book(null, goodSeat())
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_TERMS_NOT_ACCEPTED, JsonCompareMode.STRICT));

        eventAsks(3);
        book("2", goodSeat())
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_TERMS_CHANGED, JsonCompareMode.STRICT));

        book("3", """
                [{"categoryId":"%s","answers":[
                   {"questionId":"%s","value":"M"},
                   {"questionId":"%s","value":"Chipo"},
                   {"questionId":"%s","value":"18/03/1994"}]},
                 {"categoryId":"%s","answers":[{"questionId":"%s","value":"S"},{"questionId":"%s","value":"1990-01-01"}]}]
                """.formatted(categoryId, SIZE, CONTACT, BIRTH, categoryId, SIZE, BIRTH))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_ANSWERS_INVALID, JsonCompareMode.STRICT));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isZero();
        assertThat(remaining(categoryId)).isEqualTo(100);
    }

    @Test
    void theReport_needsAnEventId() throws Exception {
        mvc.perform(get("/event-organizer/reports/booking-questions").header("Authorization", organizerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "eventId": "Required." } }
                        """, JsonCompareMode.STRICT));
    }
}
