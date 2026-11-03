package com.innbucks.bookingservice.answers;

import com.innbucks.bookingservice.dto.BookingAnswerDTO;
import com.innbucks.bookingservice.dto.BookingQuestionDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a buyer must accept and answer at POST /bookings (V32), checked against
 * the event's live terms and questions before anything is held.
 */
class BookingAnswersTest {

    private static final UUID RACE_21K = UUID.randomUUID();
    private static final UUID FUN_RUN = UUID.randomUUID();
    private static final UUID SIZE = UUID.randomUUID();
    private static final UUID CONTACT = UUID.randomUUID();
    private static final UUID BIRTH = UUID.randomUUID();
    private static final UUID NEWS = UUID.randomUUID();
    private static final UUID PACE = UUID.randomUUID();
    private static final UUID FIT = UUID.randomUUID();

    private static final List<BookingQuestionDTO> QUESTIONS = List.of(
            q(SIZE, "T-shirt size", "CHOICE", true, List.of("S", "M", "L"), null, List.of()),
            q(CONTACT, "Emergency contact", "TEXT", true, List.of(), 20, List.of()),
            q(BIRTH, "Date of birth", "DATE", true, List.of(), null, List.of(RACE_21K)),
            q(NEWS, "Send me news", "CHECKBOX", false, List.of(), null, List.of()),
            q(PACE, "Expected pace (min/km)", "NUMBER", false, List.of(), null, List.of()));

    // ---- terms ------------------------------------------------------------------

    @Test
    void anEventWithTerms_needsTheCurrentVersionAccepted() {
        EventSalesWindowDTO window = window(2, List.of());

        ApiProblemException missing = assertThrows(ApiProblemException.class,
                () -> BookingAnswers.check(window, request(null, seat(FUN_RUN))));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        assertEquals(BookingAnswers.TERMS_NOT_ACCEPTED, missing.getErrorCode());
        assertEquals("Please accept the event's terms and conditions.", missing.getMessage());
        assertEquals(2, missing.getExtra().get("termsVersion"));

        ApiProblemException stale = assertThrows(ApiProblemException.class,
                () -> BookingAnswers.check(window, request(1, seat(FUN_RUN))));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        assertEquals(BookingAnswers.TERMS_CHANGED, stale.getErrorCode());
        assertEquals("The event's terms and conditions have changed. Please read them and accept again.",
                stale.getMessage());
        assertEquals(2, stale.getExtra().get("termsVersion"));

        assertEquals(2, BookingAnswers.check(window, request(2, seat(FUN_RUN))).acceptedTermsVersion());
    }

    @Test
    void anEventWithoutTerms_ignoresAnAcceptedVersion() {
        assertNull(BookingAnswers.check(window(null, List.of()), request(3, seat(FUN_RUN))).acceptedTermsVersion());
        assertNull(BookingAnswers.check(window(null, List.of()), request(null, seat(FUN_RUN))).acceptedTermsVersion());
    }

    @Test
    void termsAreCheckedBeforeAnswers() {
        ApiProblemException refused = assertThrows(ApiProblemException.class,
                () -> BookingAnswers.check(window(1, QUESTIONS), request(null, seat(RACE_21K))));
        assertEquals(BookingAnswers.TERMS_NOT_ACCEPTED, refused.getErrorCode());
    }

    // ---- answers ----------------------------------------------------------------

    @Test
    void goodAnswers_areKeptInTheEventsOrder_withTheQuestionsLabelAndType_canonicalised() {
        BookingAnswers.Checked checked = BookingAnswers.check(window(null, QUESTIONS), request(null,
                seat(RACE_21K,
                        a(NEWS, "TRUE"),
                        a(PACE, " 5.5 "),
                        a(BIRTH, "1990-07-02"),
                        a(CONTACT, "  Tariro\r\n0772000222  "),
                        a(SIZE, "m")),
                seat(FUN_RUN, a(SIZE, "S"), a(CONTACT, "Chipo"))));

        List<BookingAnswerDTO> first = checked.forTicket(0);
        assertEquals(List.of(SIZE, CONTACT, BIRTH, NEWS, PACE), first.stream().map(BookingAnswerDTO::getQuestionId).toList());
        assertEquals("M", first.get(0).getValue(), "a choice is stored as the organizer wrote it");
        assertEquals("T-shirt size", first.get(0).getLabel());
        assertEquals("CHOICE", first.get(0).getType());
        assertEquals("Tariro\n0772000222", first.get(1).getValue(), "trimmed, line breaks kept as \\n");
        assertEquals("1990-07-02", first.get(2).getValue());
        assertEquals("true", first.get(3).getValue());
        assertEquals("5.5", first.get(4).getValue());
        assertEquals(2, checked.forTicket(1).size(), "the fun run is not asked the date of birth");
        assertTrue(checked.anyAnswers());
    }

    @Test
    void anEventWithoutQuestions_keepsNothing() {
        BookingAnswers.Checked checked = BookingAnswers.check(window(null, null), request(null, seat(FUN_RUN)));
        assertEquals(List.of(), checked.forTicket(0));
        assertFalse(checked.anyAnswers());
    }

    @Test
    void everyProblemIsNamedAtOnce_byTicketAndQuestion() {
        UUID removed = UUID.randomUUID();
        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> BookingAnswers.check(
                window(null, QUESTIONS), request(null,
                        seat(RACE_21K,
                                a(SIZE, "XXL"),
                                a(CONTACT, "x".repeat(21)),
                                a(BIRTH, "02/07/1990"),
                                a(NEWS, "yes"),
                                a(PACE, "fast")),
                        seat(FUN_RUN,
                                a(BIRTH, "1990-07-02"),
                                a(removed, "anything"),
                                a(SIZE, "S"), a(SIZE, "M")),
                        seat(FUN_RUN, a(CONTACT, "Bad‮name")))));

        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatus());
        assertEquals(BookingAnswers.ANSWERS_INVALID, refused.getErrorCode());
        assertEquals("Please check your answers.", refused.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, String> answers = (Map<String, String>) refused.getExtra().get("answers");
        assertEquals("Choose one of the options.", answers.get("seats[0]." + SIZE));
        assertEquals("Must be at most 20 characters.", answers.get("seats[0]." + CONTACT));
        assertEquals("Must be a date like 1990-05-21.", answers.get("seats[0]." + BIRTH));
        assertEquals("Must be true or false.", answers.get("seats[0]." + NEWS));
        assertEquals("Must be a number.", answers.get("seats[0]." + PACE));
        assertEquals("This question is not asked for this ticket.", answers.get("seats[1]." + BIRTH));
        assertEquals("This question is no longer asked. Refresh the event and try again.",
                answers.get("seats[1]." + removed));
        assertEquals("Answered more than once.", answers.get("seats[1]." + SIZE));
        assertEquals("This question is required.", answers.get("seats[1]." + CONTACT));
        assertEquals("Contains characters that are not allowed.", answers.get("seats[2]." + CONTACT));
        assertEquals("This question is required.", answers.get("seats[2]." + SIZE));
    }

    @Test
    void aRequiredCheckbox_mustBeTicked_andAnOptionalOneMayBeLeftOut() {
        List<BookingQuestionDTO> consent = List.of(q(FIT, "I am fit to run", "CHECKBOX", true, List.of(), null, List.of()));

        @SuppressWarnings("unchecked")
        Map<String, String> unticked = (Map<String, String>) assertThrows(ApiProblemException.class,
                () -> BookingAnswers.check(window(null, consent), request(null, seat(FUN_RUN, a(FIT, "false")))))
                .getExtra().get("answers");
        assertEquals("Tick this box to continue.", unticked.get("seats[0]." + FIT));

        @SuppressWarnings("unchecked")
        Map<String, String> absent = (Map<String, String>) assertThrows(ApiProblemException.class,
                () -> BookingAnswers.check(window(null, consent), request(null, seat(FUN_RUN))))
                .getExtra().get("answers");
        assertEquals("Tick this box to continue.", absent.get("seats[0]." + FIT));

        assertEquals("true", BookingAnswers.check(window(null, consent),
                request(null, seat(FUN_RUN, a(FIT, "true")))).forTicket(0).get(0).getValue());
    }

    @Test
    void aBlankOptionalAnswer_isTreatedAsSkipped() {
        BookingAnswers.Checked checked = BookingAnswers.check(window(null, QUESTIONS), request(null,
                seat(FUN_RUN, a(SIZE, "L"), a(CONTACT, "Chipo"), a(PACE, "  "))));
        assertEquals(List.of(SIZE, CONTACT), checked.forTicket(0).stream().map(BookingAnswerDTO::getQuestionId).toList());
    }

    @Test
    void textAllowsLineBreaksAndTabs_butNoOtherControlOrFormatCharacters() {
        assertTrue(BookingAnswers.allowedText("Line one\nLine two\twith a tab, and émoji 🎽"));
        assertFalse(BookingAnswers.allowedText("bell\u0007"));
        assertFalse(BookingAnswers.allowedText("zero​width"));
        assertFalse(BookingAnswers.allowedText("line separator"));
    }

    // ---- fixtures ---------------------------------------------------------------

    private static BookingQuestionDTO q(UUID id, String label, String type, boolean required, List<String> options,
                                        Integer maxLength, List<UUID> categoryIds) {
        return BookingQuestionDTO.builder().id(id).label(label).type(type).required(required).options(options)
                .maxLength(maxLength).categoryIds(categoryIds).build();
    }

    private static EventSalesWindowDTO window(Integer termsVersion, List<BookingQuestionDTO> questions) {
        return EventSalesWindowDTO.builder().status(EventSalesWindowDTO.ON_SALE)
                .termsVersion(termsVersion).bookingQuestions(questions).build();
    }

    private static CreateBookingRequestDTO.AnswerRequest a(UUID questionId, String value) {
        CreateBookingRequestDTO.AnswerRequest a = new CreateBookingRequestDTO.AnswerRequest();
        a.setQuestionId(questionId);
        a.setValue(value);
        return a;
    }

    private static CreateBookingRequestDTO.SeatItemRequest seat(UUID categoryId,
                                                                CreateBookingRequestDTO.AnswerRequest... answers) {
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(categoryId);
        seat.setAnswers(answers.length == 0 ? null : new ArrayList<>(List.of(answers)));
        return seat;
    }

    private static CreateBookingRequestDTO request(Integer acceptedTermsVersion,
                                                   CreateBookingRequestDTO.SeatItemRequest... seats) {
        CreateBookingRequestDTO r = new CreateBookingRequestDTO();
        r.setEventId(UUID.randomUUID());
        r.setAcceptedTermsVersion(acceptedTermsVersion);
        r.setSeats(List.of(seats));
        return r;
    }
}
