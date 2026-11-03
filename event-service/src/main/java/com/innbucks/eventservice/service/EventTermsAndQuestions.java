package com.innbucks.eventservice.service;

import com.innbucks.eventservice.dto.BookingQuestionDTO;
import com.innbucks.eventservice.dto.BookingQuestionsRequestDTO;
import com.innbucks.eventservice.dto.EventTermsDTO;
import com.innbucks.eventservice.entity.BookingQuestionType;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventBookingQuestion;
import com.innbucks.eventservice.entity.EventTermsVersion;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.repository.EventBookingQuestionRepository;
import com.innbucks.eventservice.repository.EventTermsVersionRepository;
import com.innbucks.eventservice.util.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An event's terms and conditions and its booking questions (V16): what a
 * buyer must accept and answer before booking. {@link EventService} owns the
 * event row and its access rules and calls in here; booking-service reads
 * both through the sales-window answer and enforces them at
 * {@code POST /bookings}.
 *
 * <p><b>Terms are versioned and append-only.</b> A booking records the version
 * it accepted, so every version's text stays readable: a change writes the
 * next version, the same text writes nothing, and removing the terms only
 * clears {@link Event#getTermsVersion()}. A version number is never reused,
 * even after removal, so "version 2" always means the same text.
 *
 * <p><b>Questions are replaced as a whole.</b> A question sent with its id is
 * updated in place (answers stay linked to it); one without an id is new; a
 * stored one left out is deleted. booking-service copies each question's label
 * and type into the answer, so nothing here can change a past booking.
 */
@Service
@Slf4j
public class EventTermsAndQuestions {

    public static final int MAX_QUESTIONS = 10;
    public static final int MAX_LABEL = 80;
    public static final int MAX_HELP_TEXT = 200;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 20;
    public static final int MAX_OPTION = 60;
    public static final int MAX_TEXT_LENGTH = 500;
    public static final int DEFAULT_TEXT_LENGTH = 100;
    public static final int MAX_CATEGORIES = 50;

    static final String TOO_MANY = "An event can ask at most 10 questions.";
    static final String BAD_LABEL = "Give the question a label of at most 80 characters.";
    static final String DUPLICATE_LABEL = "Two questions can't have the same label.";
    static final String BAD_TYPE = "Type must be TEXT, NUMBER, DATE, CHOICE or CHECKBOX.";
    static final String BAD_OPTIONS = "A choice question needs 2 to 20 different options of at most 60 characters.";
    static final String OPTIONS_NOT_CHOICE = "Only a choice question has options.";
    static final String BAD_MAX_LENGTH = "Must be between 1 and 500.";
    static final String MAX_LENGTH_NOT_TEXT = "Only a text question has a maximum length.";
    static final String BAD_HELP_TEXT = "Must be at most 200 characters.";
    static final String TOO_MANY_CATEGORIES = "At most 50 categories.";
    static final String FOREIGN_ID = "This question is not one of this event's. Leave id out for a new question.";

    private final EventTermsVersionRepository termsRepository;
    private final EventBookingQuestionRepository questionRepository;
    private AuditorAware<String> auditor;

    public EventTermsAndQuestions(EventTermsVersionRepository termsRepository,
                                  EventBookingQuestionRepository questionRepository) {
        this.termsRepository = termsRepository;
        this.questionRepository = questionRepository;
    }

    /** Who saved a terms version; without one (hand-built tests) the column stays null. */
    @Autowired(required = false)
    public void setAuditor(AuditorAware<String> auditor) {
        this.auditor = auditor;
    }

    // ---- terms ---------------------------------------------------------------

    /**
     * The text as it is stored: HTML stripped line by line (so line breaks
     * survive), Windows line ends folded to {@code \n}, outer blank space
     * trimmed. Null when nothing is left: no terms.
     */
    public static String cleanTerms(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(line -> {
                    String stripped = HtmlSanitizer.stripAll(line);
                    return stripped == null ? "" : stripped.stripTrailing();
                })
                .collect(Collectors.joining("\n"))
                .strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Writes version 1 for a new event; call after the event row is saved with {@code termsVersion = 1}. */
    public void recordFirstVersion(UUID eventId, String cleanedText) {
        termsRepository.save(version(eventId, 1, cleanedText));
        log.info("Event terms saved eventId={} version=1 length={}", eventId, cleanedText.length());
    }

    /**
     * Applies an edit's {@code termsText} to the event: the same text changes
     * nothing, other text writes the next version, blank removes the terms.
     */
    public void applyEdit(Event event, String rawText) {
        String text = cleanTerms(rawText);
        if (text == null) {
            if (event.getTermsVersion() != null) {
                log.info("Event terms removed eventId={} wasVersion={}", event.getEventId(), event.getTermsVersion());
            }
            event.setTermsVersion(null);
            return;
        }
        if (event.getTermsVersion() != null) {
            String current = termsRepository.findByEventIdAndVersion(event.getEventId(), event.getTermsVersion())
                    .map(EventTermsVersion::getText).orElse(null);
            if (text.equals(current)) {
                return;
            }
        }
        // Never reuse a number, even one whose terms were later removed.
        int next = termsRepository.maxVersion(event.getEventId()) + 1;
        termsRepository.save(version(event.getEventId(), next, text));
        event.setTermsVersion(next);
        log.info("Event terms saved eventId={} version={} length={}", event.getEventId(), next, text.length());
    }

    /** The current text, or null when the event has no terms. */
    public String currentText(Event event) {
        if (event.getTermsVersion() == null) {
            return null;
        }
        return termsRepository.findByEventIdAndVersion(event.getEventId(), event.getTermsVersion())
                .map(EventTermsVersion::getText).orElse(null);
    }

    public EventTermsDTO terms(UUID eventId, int version) {
        return termsRepository.findByEventIdAndVersion(eventId, version)
                .map(t -> EventTermsDTO.builder()
                        .eventId(t.getEventId())
                        .version(t.getVersion())
                        .text(t.getText())
                        .createdAt(t.getCreatedAt())
                        .build())
                .orElseThrow(() -> new NotFoundException("Terms not found"));
    }

    private EventTermsVersion version(UUID eventId, int version, String text) {
        String by = auditor == null ? null : auditor.getCurrentAuditor().orElse(null);
        return EventTermsVersion.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .version(version)
                .text(text)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .createdBy(by)
                .build();
    }

    // ---- questions -------------------------------------------------------------

    public List<BookingQuestionDTO> questions(UUID eventId) {
        return questionRepository.findByEventIdOrderByPositionAsc(eventId).stream()
                .map(EventTermsAndQuestions::toDto)
                .toList();
    }

    /**
     * Replaces the event's questions with {@code request.questions}, after
     * checking every one of them: a refusal names each problem by its place in
     * the list and changes nothing.
     */
    public List<BookingQuestionDTO> replace(UUID eventId, BookingQuestionsRequestDTO request) {
        List<BookingQuestionDTO> wanted = request == null || request.getQuestions() == null
                ? List.of() : request.getQuestions();
        Map<UUID, EventBookingQuestion> stored = new HashMap<>();
        for (EventBookingQuestion q : questionRepository.findByEventIdOrderByPositionAsc(eventId)) {
            stored.put(q.getId(), q);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        if (wanted.size() > MAX_QUESTIONS) {
            errors.put("questions", TOO_MANY);
            throw new FieldErrorsException(errors);
        }
        List<Validated> valid = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        Set<UUID> keptIds = new HashSet<>();
        for (int i = 0; i < wanted.size(); i++) {
            Validated v = validate("questions[" + i + "]", wanted.get(i), stored, errors);
            if (v == null) {
                continue;
            }
            if (v.label() != null && !labels.add(v.label().toLowerCase(Locale.ROOT))) {
                errors.put("questions[" + i + "].label", DUPLICATE_LABEL);
                continue;
            }
            if (v.id() != null && !keptIds.add(v.id())) {
                errors.put("questions[" + i + "].id", FOREIGN_ID);
                continue;
            }
            valid.add(v);
        }
        if (!errors.isEmpty()) {
            log.info("Booking questions refused eventId={} problems={}", eventId, errors.keySet());
            throw new FieldErrorsException(errors);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<EventBookingQuestion> removed = stored.values().stream()
                .filter(q -> !keptIds.contains(q.getId()))
                .toList();
        questionRepository.deleteAll(removed);
        // Positions are rewritten from 0 below; flushing the deletes first keeps
        // a removed row from sitting beside the rows that replace it.
        questionRepository.flush();

        List<EventBookingQuestion> saved = new ArrayList<>();
        for (int position = 0; position < valid.size(); position++) {
            Validated v = valid.get(position);
            EventBookingQuestion row = v.id() == null
                    ? EventBookingQuestion.builder().id(UUID.randomUUID()).eventId(eventId).createdAt(now).build()
                    : stored.get(v.id());
            row.setPosition(position);
            row.setLabel(v.label());
            row.setType(v.type());
            row.setRequired(v.required());
            row.setHelpText(v.helpText());
            row.setOptions(new ArrayList<>(v.options()));
            row.setMaxLength(v.maxLength());
            row.setCategoryIds(v.categoryIds().stream().map(UUID::toString).collect(Collectors.toCollection(ArrayList::new)));
            row.setUpdatedAt(now);
            saved.add(row);
        }
        List<BookingQuestionDTO> result = questionRepository.saveAll(saved).stream()
                .map(EventTermsAndQuestions::toDto)
                .toList();
        log.info("Booking questions saved eventId={} count={} removed={}", eventId, result.size(), removed.size());
        return result;
    }

    private record Validated(UUID id, String label, BookingQuestionType type, boolean required, String helpText,
                             List<String> options, Integer maxLength, List<UUID> categoryIds) {
    }

    private static Validated validate(String at, BookingQuestionDTO q, Map<UUID, EventBookingQuestion> stored,
                                      Map<String, String> errors) {
        int before = errors.size();
        if (q == null) {
            errors.put(at + ".label", BAD_LABEL);
            return null;
        }
        if (q.getId() != null && !stored.containsKey(q.getId())) {
            errors.put(at + ".id", FOREIGN_ID);
        }
        String label = q.getLabel() == null ? null : HtmlSanitizer.stripAll(q.getLabel()).strip();
        if (label == null || label.isEmpty() || label.length() > MAX_LABEL || hasLineBreak(label)) {
            errors.put(at + ".label", BAD_LABEL);
        }
        BookingQuestionType type = parseType(q.getType());
        if (type == null) {
            errors.put(at + ".type", BAD_TYPE);
        }
        String helpText = q.getHelpText() == null ? null : HtmlSanitizer.stripAll(q.getHelpText()).strip();
        if (helpText != null && helpText.isEmpty()) {
            helpText = null;
        }
        if (helpText != null && helpText.length() > MAX_HELP_TEXT) {
            errors.put(at + ".helpText", BAD_HELP_TEXT);
        }
        List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
        if (type == BookingQuestionType.CHOICE) {
            options = cleanOptions(options);
            if (options == null) {
                errors.put(at + ".options", BAD_OPTIONS);
            }
        } else if (!options.isEmpty() && type != null) {
            errors.put(at + ".options", OPTIONS_NOT_CHOICE);
        }
        Integer maxLength = q.getMaxLength();
        if (type == BookingQuestionType.TEXT) {
            if (maxLength == null) {
                maxLength = DEFAULT_TEXT_LENGTH;
            } else if (maxLength < 1 || maxLength > MAX_TEXT_LENGTH) {
                errors.put(at + ".maxLength", BAD_MAX_LENGTH);
            }
        } else if (maxLength != null && type != null) {
            errors.put(at + ".maxLength", MAX_LENGTH_NOT_TEXT);
        }
        List<UUID> categoryIds = q.getCategoryIds() == null ? List.of()
                : q.getCategoryIds().stream().filter(Objects::nonNull).distinct().toList();
        if (categoryIds.size() > MAX_CATEGORIES) {
            errors.put(at + ".categoryIds", TOO_MANY_CATEGORIES);
        }
        if (errors.size() > before) {
            return null;
        }
        return new Validated(q.getId(), label, type, Boolean.TRUE.equals(q.getRequired()), helpText,
                type == BookingQuestionType.CHOICE ? options : List.of(),
                type == BookingQuestionType.TEXT ? maxLength : null,
                categoryIds);
    }

    /** Trimmed, HTML stripped, 2 to 20 of them, each 1 to 60 characters, all different in any case; else null. */
    private static List<String> cleanOptions(List<String> raw) {
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String option : raw) {
            String value = option == null ? "" : HtmlSanitizer.stripAll(option).strip();
            if (value.isEmpty() || value.length() > MAX_OPTION || hasLineBreak(value)
                    || !seen.add(value.toLowerCase(Locale.ROOT))) {
                return null;
            }
            cleaned.add(value);
        }
        return cleaned.size() < MIN_OPTIONS || cleaned.size() > MAX_OPTIONS ? null : cleaned;
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static BookingQuestionType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return BookingQuestionType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static BookingQuestionDTO toDto(EventBookingQuestion q) {
        return BookingQuestionDTO.builder()
                .id(q.getId())
                .label(q.getLabel())
                .type(q.getType().name())
                .required(q.isRequired())
                .helpText(q.getHelpText())
                .options(List.copyOf(q.getOptions()))
                .maxLength(q.getMaxLength())
                .categoryIds(q.getCategoryIds().stream().map(UUID::fromString).toList())
                .build();
    }
}
