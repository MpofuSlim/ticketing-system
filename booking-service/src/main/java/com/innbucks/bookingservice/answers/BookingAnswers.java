package com.innbucks.bookingservice.answers;

import com.innbucks.bookingservice.dto.BookingAnswerDTO;
import com.innbucks.bookingservice.dto.BookingQuestionDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checks what a buyer accepted and answered at {@code POST /bookings} against
 * what the event asks right now (event-service V16, read live through the
 * sales window), before anything is looked up or held.
 *
 * <ul>
 *   <li><b>Terms:</b> an event with terms needs {@code acceptedTermsVersion}
 *       (400 {@code TERMS_NOT_ACCEPTED}), and it must be the current version
 *       (409 {@code TERMS_CHANGED}, the buyer re-reads them). An event without
 *       terms ignores the field.</li>
 *   <li><b>Answers:</b> each ticket answers the questions whose
 *       {@code categoryIds} is empty or names its category. Every problem is
 *       collected and answered at once (400 {@code ANSWERS_INVALID},
 *       {@code data.answers} = {@code "seats[i].<questionId>"} to message).</li>
 * </ul>
 *
 * <p>The answers kept carry each question's label and type as they are now,
 * in the event's question order, so a later rename or removal never changes
 * what a booking recorded. Event-service's own copy of the question rules is
 * {@code EventTermsAndQuestions}; change the two together.
 */
public final class BookingAnswers {

    public static final String TERMS_NOT_ACCEPTED = "TERMS_NOT_ACCEPTED";
    public static final String TERMS_CHANGED = "TERMS_CHANGED";
    public static final String ANSWERS_INVALID = "ANSWERS_INVALID";

    static final String TERMS_NOT_ACCEPTED_MESSAGE = "Please accept the event's terms and conditions.";
    static final String TERMS_CHANGED_MESSAGE =
            "The event's terms and conditions have changed. Please read them and accept again.";
    static final String ANSWERS_INVALID_MESSAGE = "Please check your answers.";

    static final String REQUIRED = "This question is required.";
    static final String TICK = "Tick this box to continue.";
    static final String NOT_NUMBER = "Must be a number.";
    static final String NOT_DATE = "Must be a date like 1990-05-21.";
    static final String NOT_OPTION = "Choose one of the options.";
    static final String NOT_BOOLEAN = "Must be true or false.";
    static final String BAD_CHARACTERS = "Contains characters that are not allowed.";
    static final String NOT_FOR_TICKET = "This question is not asked for this ticket.";
    static final String TWICE = "Answered more than once.";
    static final String NO_LONGER_ASKED = "This question is no longer asked. Refresh the event and try again.";

    private static final int DEFAULT_TEXT_LENGTH = 100;
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,12}(\\.\\d{1,4})?");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private BookingAnswers() {
    }

    /** What a booking records: the version it accepted, and each ticket's answers in request order. */
    public record Checked(Integer acceptedTermsVersion, List<List<BookingAnswerDTO>> perTicket) {

        public List<BookingAnswerDTO> forTicket(int index) {
            return index < perTicket.size() ? perTicket.get(index) : List.of();
        }

        public boolean anyAnswers() {
            return perTicket.stream().anyMatch(a -> !a.isEmpty());
        }
    }

    public static Checked check(EventSalesWindowDTO window, CreateBookingRequestDTO request) {
        Integer accepted = checkTerms(window.getTermsVersion(), request.getAcceptedTermsVersion());
        List<BookingQuestionDTO> questions = window.getBookingQuestions() == null
                ? List.of() : window.getBookingQuestions();
        Map<UUID, BookingQuestionDTO> byId = new HashMap<>();
        for (BookingQuestionDTO q : questions) {
            byId.put(q.getId(), q);
        }

        Map<String, String> problems = new LinkedHashMap<>();
        List<List<BookingAnswerDTO>> perTicket = new ArrayList<>(request.getSeats().size());
        for (int i = 0; i < request.getSeats().size(); i++) {
            CreateBookingRequestDTO.SeatItemRequest seat = request.getSeats().get(i);
            perTicket.add(checkTicket("seats[" + i + "].", seat, questions, byId, problems));
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ANSWERS_INVALID, ANSWERS_INVALID_MESSAGE,
                    Map.of("answers", problems));
        }
        return new Checked(accepted, perTicket);
    }

    private static Integer checkTerms(Integer current, Integer accepted) {
        if (current == null) {
            return null;
        }
        if (accepted == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, TERMS_NOT_ACCEPTED, TERMS_NOT_ACCEPTED_MESSAGE,
                    Map.of("termsVersion", current));
        }
        if (!current.equals(accepted)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, TERMS_CHANGED, TERMS_CHANGED_MESSAGE,
                    Map.of("termsVersion", current));
        }
        return current;
    }

    private static List<BookingAnswerDTO> checkTicket(String at, CreateBookingRequestDTO.SeatItemRequest seat,
                                                      List<BookingQuestionDTO> questions,
                                                      Map<UUID, BookingQuestionDTO> byId,
                                                      Map<String, String> problems) {
        Map<UUID, String> given = new HashMap<>();
        List<CreateBookingRequestDTO.AnswerRequest> answers = seat.getAnswers() == null ? List.of() : seat.getAnswers();
        for (CreateBookingRequestDTO.AnswerRequest answer : answers) {
            UUID id = answer.getQuestionId();
            String key = at + id;
            BookingQuestionDTO q = byId.get(id);
            if (q == null) {
                problems.putIfAbsent(key, NO_LONGER_ASKED);
            } else if (!asks(q, seat.getCategoryId())) {
                problems.putIfAbsent(key, NOT_FOR_TICKET);
            } else if (given.containsKey(id)) {
                problems.put(key, TWICE);
            } else {
                given.put(id, answer.getValue());
            }
        }

        List<BookingAnswerDTO> kept = new ArrayList<>();
        for (BookingQuestionDTO q : questions) {
            if (!asks(q, seat.getCategoryId())) {
                continue;
            }
            String key = at + q.getId();
            if (problems.containsKey(key)) {
                continue;
            }
            String raw = given.get(q.getId());
            boolean blank = raw == null || raw.isBlank();
            boolean required = Boolean.TRUE.equals(q.getRequired());
            if (blank) {
                if (required) {
                    problems.put(key, "CHECKBOX".equals(q.getType()) ? TICK : REQUIRED);
                }
                continue;
            }
            Result r = value(q, raw);
            if (r.problem() != null) {
                problems.put(key, r.problem());
            } else if (required && "CHECKBOX".equals(q.getType()) && !"true".equals(r.value())) {
                problems.put(key, TICK);
            } else {
                kept.add(BookingAnswerDTO.builder()
                        .questionId(q.getId()).label(q.getLabel()).type(q.getType()).value(r.value()).build());
            }
        }
        return kept;
    }

    private static boolean asks(BookingQuestionDTO q, UUID categoryId) {
        return q.getCategoryIds() == null || q.getCategoryIds().isEmpty() || q.getCategoryIds().contains(categoryId);
    }

    private record Result(String value, String problem) {
        static Result ok(String value) {
            return new Result(value, null);
        }

        static Result refused(String problem) {
            return new Result(null, problem);
        }
    }

    /** The value as stored, or the problem with it. */
    private static Result value(BookingQuestionDTO q, String raw) {
        String type = q.getType() == null ? "" : q.getType();
        switch (type) {
            case "NUMBER" -> {
                String v = raw.strip();
                return NUMBER.matcher(v).matches() ? Result.ok(v) : Result.refused(NOT_NUMBER);
            }
            case "DATE" -> {
                String v = raw.strip();
                try {
                    return Result.ok(LocalDate.parse(v, DATE).toString());
                } catch (DateTimeParseException e) {
                    return Result.refused(NOT_DATE);
                }
            }
            case "CHOICE" -> {
                String v = raw.strip();
                for (String option : q.getOptions() == null ? List.<String>of() : q.getOptions()) {
                    if (option.equalsIgnoreCase(v)) {
                        return Result.ok(option);
                    }
                }
                return Result.refused(NOT_OPTION);
            }
            case "CHECKBOX" -> {
                String v = raw.strip().toLowerCase(Locale.ROOT);
                return "true".equals(v) || "false".equals(v) ? Result.ok(v) : Result.refused(NOT_BOOLEAN);
            }
            default -> {
                // TEXT, and a type this build does not know: kept as text, bounded.
                String v = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
                if (!allowedText(v)) {
                    return Result.refused(BAD_CHARACTERS);
                }
                int max = q.getMaxLength() == null ? DEFAULT_TEXT_LENGTH : q.getMaxLength();
                return v.codePointCount(0, v.length()) > max
                        ? Result.refused("Must be at most " + max + " characters.")
                        : Result.ok(v);
            }
        }
    }

    /**
     * No control characters but line breaks and tabs, and no bidi controls or
     * invisible format characters: a guest list rendered as a spreadsheet or a
     * PDF must read as it was typed.
     */
    static boolean allowedText(String v) {
        return v.codePoints().allMatch(c -> {
            if (c == '\n' || c == '\t') {
                return true;
            }
            if (Character.isISOControl(c)) {
                return false;
            }
            int t = Character.getType(c);
            return t != Character.FORMAT && t != Character.LINE_SEPARATOR && t != Character.PARAGRAPH_SEPARATOR;
        });
    }
}
