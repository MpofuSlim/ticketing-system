package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.BookingGateway;
import com.innbucks.eventservice.client.BookingNotificationGateway;
import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.OrganizerNotificationGateway;
import com.innbucks.eventservice.client.SeatCategoryGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.dto.BookingQuestionDTO;
import com.innbucks.eventservice.dto.BookingQuestionsRequestDTO;
import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.SalesWindowDTO;
import com.innbucks.eventservice.dto.UpdateEventRequestDTO;
import com.innbucks.eventservice.entity.BookingQuestionType;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventBookingQuestion;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.entity.EventTermsVersion;
import com.innbucks.eventservice.entity.JsonStringListConverter;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.exception.ForbiddenException;
import com.innbucks.eventservice.exception.NotFoundException;
import com.innbucks.eventservice.mapper.EventMapper;
import com.innbucks.eventservice.repository.EventBookingQuestionRepository;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventTermsVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Event terms and booking questions (V16): the stored text, its versions, the
 * questions' rules, and what the event answers and the sales window carry.
 */
class EventTermsAndQuestionsTest {

    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID RACE_21K = UUID.fromString("8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11");

    private final EventTermsVersionRepository termsRepo = mock(EventTermsVersionRepository.class);
    private final EventBookingQuestionRepository questionRepo = mock(EventBookingQuestionRepository.class);
    private final EventTermsAndQuestions terms = new EventTermsAndQuestions(termsRepo, questionRepo);
    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventService service = new EventService(eventRepo, new MarketTimeZone("ZW"), new EventMapper(),
            mock(SeatCategoryGateway.class), mock(BookingGateway.class), mock(OrganizerGateway.class),
            mock(BookingNotificationGateway.class), mock(OrganizerNotificationGateway.class));

    @BeforeEach
    void wire() {
        service.setTermsAndQuestions(terms);
        when(questionRepo.saveAll(anyList())).thenAnswer(inv -> new ArrayList<>((Collection<?>) inv.getArgument(0)));
        when(termsRepo.save(any(EventTermsVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            if (e.getEventId() == null) {
                e.setEventId(EVENT);
            }
            return e;
        });
    }

    // ---- terms text ------------------------------------------------------------

    @Test
    void cleanTerms_keepsLineBreaks_stripsHtml_andTreatsBlankAsNone() {
        assertEquals("1. No refunds.\n2. Bring <ID>.",
                EventTermsAndQuestions.cleanTerms("  1. <b>No</b> refunds.\r\n2. Bring &lt;ID&gt;.  \n\n"),
                "tags go, the escaped text is kept as text, line breaks stay, outer blank space goes");
        assertNull(EventTermsAndQuestions.cleanTerms("   \n "));
        assertNull(EventTermsAndQuestions.cleanTerms(null));
        assertNull(EventTermsAndQuestions.cleanTerms("<p></p>"), "nothing but markup is no terms");
    }

    @Test
    void create_withTerms_storesVersion1_andAnswersTheText() {
        CreateEventRequestDTO req = createRequest();
        req.setTermsText("1. No refunds.\n2. Bring ID.");

        EventResponseDTO created = service.createEvent(ORGANIZER, "Zimbabwe", req);

        ArgumentCaptor<EventTermsVersion> version = ArgumentCaptor.forClass(EventTermsVersion.class);
        verify(termsRepo).save(version.capture());
        assertEquals(1, version.getValue().getVersion());
        assertEquals(EVENT, version.getValue().getEventId());
        assertEquals("1. No refunds.\n2. Bring ID.", version.getValue().getText());
        assertEquals(1, created.getTermsVersion());
        assertEquals("1. No refunds.\n2. Bring ID.", created.getTermsText());
    }

    @Test
    void create_withoutTerms_storesNone() {
        EventResponseDTO created = service.createEvent(ORGANIZER, "Zimbabwe", createRequest());

        verify(termsRepo, never()).save(any());
        assertNull(created.getTermsVersion());
        assertNull(created.getTermsText());
    }

    @Test
    void edit_differentTextIsTheNextVersion_neverReusingANumber() {
        Event event = published(2);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(event));
        when(termsRepo.findByEventIdAndVersion(EVENT, 2)).thenReturn(Optional.of(stored(2, "Old.")));
        // Version 3 was saved once and then removed: the next one is 4.
        when(termsRepo.maxVersion(EVENT)).thenReturn(3);

        service.updateEvent(ORGANIZER, EVENT, update("termsText", "New."));

        ArgumentCaptor<EventTermsVersion> version = ArgumentCaptor.forClass(EventTermsVersion.class);
        verify(termsRepo).save(version.capture());
        assertEquals(4, version.getValue().getVersion());
        assertEquals(4, event.getTermsVersion());
    }

    @Test
    void edit_sameTextChangesNothing_andAbsentLeavesTheTerms() {
        Event event = published(2);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(event));
        when(termsRepo.findByEventIdAndVersion(EVENT, 2)).thenReturn(Optional.of(stored(2, "Same.")));

        service.updateEvent(ORGANIZER, EVENT, update("termsText", "  Same.  "));
        service.updateEvent(ORGANIZER, EVENT, update("title", "Renamed"));

        verify(termsRepo, never()).save(any());
        assertEquals(2, event.getTermsVersion());
    }

    @Test
    void edit_nullOrBlankRemovesTheTerms_keepingEveryVersion() {
        Event event = published(2);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(event));

        EventResponseDTO answer = service.updateEvent(ORGANIZER, EVENT, update("termsText", null));

        assertNull(event.getTermsVersion());
        assertNull(answer.getTermsText());
        verify(termsRepo, never()).delete(any());
        verify(termsRepo, never()).save(any());

        Event again = published(5);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(again));
        service.updateEvent(ORGANIZER, EVENT, update("termsText", ""));
        assertNull(again.getTermsVersion());
    }

    @Test
    void termsVersion_readsBackItsText_andAnUnknownVersionIs404() {
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(published(2)));
        when(termsRepo.findByEventIdAndVersion(EVENT, 1)).thenReturn(Optional.of(stored(1, "First.")));

        assertEquals("First.", service.getTerms(EVENT, 1).getText());
        NotFoundException missing = assertThrows(NotFoundException.class, () -> service.getTerms(EVENT, 9));
        assertEquals("Terms not found", missing.getMessage());
    }

    @Test
    void termsOfADraft_areThe404OfTheDraft_forAnAnonymousReader() {
        Event draft = published(1);
        draft.setActive(false);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(draft));

        NotFoundException hidden = assertThrows(NotFoundException.class, () -> service.getTerms(EVENT, 1));
        assertEquals("Event not found", hidden.getMessage());
        verify(termsRepo, never()).findByEventIdAndVersion(any(), anyInt());
    }

    @Test
    void updateDto_tellsAnExplicitNullTermsFromAnAbsentOne() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        assertTrue(json.readValue("{\"termsText\":null}", UpdateEventRequestDTO.class).isTermsTextSent());
        assertFalse(json.readValue("{\"title\":\"x\"}", UpdateEventRequestDTO.class).isTermsTextSent());
        assertFalse(json.readValue("{\"termsTextSent\":true}", UpdateEventRequestDTO.class).isTermsTextSent(),
                "the flag is set by Jackson only, never bound from the body");
    }

    // ---- questions ---------------------------------------------------------------

    @Test
    void replace_savesTheListInOrder_newOnesGetIds_keptOnesKeepTheirs_leftOutOnesGo() {
        EventBookingQuestion kept = row(UUID.randomUUID(), 0, "T-shirt size", BookingQuestionType.CHOICE);
        EventBookingQuestion dropped = row(UUID.randomUUID(), 1, "Old question", BookingQuestionType.TEXT);
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of(kept, dropped));

        List<BookingQuestionDTO> saved = terms.replace(EVENT, request(
                question(null, "Date of birth", "DATE", true, null, null, List.of(RACE_21K)),
                question(kept.getId(), " T-shirt <b>size</b> ", "choice", true, List.of("S", "M", "L"), null, null),
                question(null, "Emergency contact", "TEXT", true, null, 120, null),
                question(null, "Send me news", "CHECKBOX", false, List.of(), null, List.of())));

        verify(questionRepo).deleteAll(List.of(dropped));
        assertEquals(4, saved.size());
        assertNotNull(saved.get(0).getId());
        assertEquals("Date of birth", saved.get(0).getLabel());
        assertEquals(List.of(RACE_21K), saved.get(0).getCategoryIds());
        assertEquals(kept.getId(), saved.get(1).getId(), "a kept question keeps its id");
        assertEquals("T-shirt size", saved.get(1).getLabel(), "trimmed, markup stripped");
        assertEquals("CHOICE", saved.get(1).getType());
        assertEquals(List.of("S", "M", "L"), saved.get(1).getOptions());
        assertEquals(1, kept.getPosition(), "moved to the place it was sent in");
        assertEquals(120, saved.get(2).getMaxLength());
        assertEquals(List.of(), saved.get(3).getOptions());
        assertFalse(saved.get(3).getRequired());
    }

    @Test
    void replace_aTextQuestionWithoutAMaxLengthGets100() {
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of());

        List<BookingQuestionDTO> saved = terms.replace(EVENT, request(
                question(null, "Medical notes", "TEXT", false, null, null, null)));

        assertEquals(100, saved.get(0).getMaxLength());
    }

    @Test
    void replace_anEmptyListRemovesEveryQuestion() {
        EventBookingQuestion q = row(UUID.randomUUID(), 0, "T-shirt size", BookingQuestionType.CHOICE);
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of(q));

        assertEquals(List.of(), terms.replace(EVENT, request()));
        verify(questionRepo).deleteAll(List.of(q));
    }

    @Test
    void replace_namesEveryProblemByItsPlace_andChangesNothing() {
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of());
        String long81 = "x".repeat(81);

        FieldErrorsException refused = assertThrows(FieldErrorsException.class, () -> terms.replace(EVENT, request(
                question(null, "Size", "CHOICE", true, List.of("S"), null, null),
                question(null, long81, "TEXT", false, null, null, null),
                question(null, "Age", "AGE", false, null, null, null),
                question(null, "Name", "TEXT", false, List.of("a", "b"), 600, null),
                question(null, "When", "DATE", false, null, 10, null),
                question(UUID.randomUUID(), "Elsewhere", "TEXT", false, null, null, null),
                question(null, "Tip", "TEXT", false, null, null, null, "h".repeat(201)))));

        Map<String, String> errors = refused.getErrors();
        assertEquals(EventTermsAndQuestions.BAD_OPTIONS, errors.get("questions[0].options"));
        assertEquals(EventTermsAndQuestions.BAD_LABEL, errors.get("questions[1].label"));
        assertEquals(EventTermsAndQuestions.BAD_TYPE, errors.get("questions[2].type"));
        assertEquals(EventTermsAndQuestions.OPTIONS_NOT_CHOICE, errors.get("questions[3].options"));
        assertEquals(EventTermsAndQuestions.BAD_MAX_LENGTH, errors.get("questions[3].maxLength"));
        assertEquals(EventTermsAndQuestions.MAX_LENGTH_NOT_TEXT, errors.get("questions[4].maxLength"));
        assertEquals(EventTermsAndQuestions.FOREIGN_ID, errors.get("questions[5].id"));
        assertEquals(EventTermsAndQuestions.BAD_HELP_TEXT, errors.get("questions[6].helpText"));
        verify(questionRepo, never()).deleteAll(any());
        verify(questionRepo, never()).saveAll(any());
    }

    @Test
    void replace_refusesDuplicateLabelsAndOptions_inAnyLetterCase() {
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of());

        FieldErrorsException refused = assertThrows(FieldErrorsException.class, () -> terms.replace(EVENT, request(
                question(null, "T-shirt size", "CHOICE", true, List.of("S", "s"), null, null),
                question(null, "Club", "TEXT", false, null, null, null),
                question(null, "CLUB", "TEXT", false, null, null, null))));

        assertEquals(EventTermsAndQuestions.BAD_OPTIONS, refused.getErrors().get("questions[0].options"));
        assertEquals(EventTermsAndQuestions.DUPLICATE_LABEL, refused.getErrors().get("questions[2].label"));
    }

    @Test
    void replace_refusesMoreThanTenQuestions_andMoreThan50Categories() {
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of());
        BookingQuestionDTO[] eleven = new BookingQuestionDTO[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = question(null, "Q" + i, "TEXT", false, null, null, null);
        }
        assertEquals(EventTermsAndQuestions.TOO_MANY,
                assertThrows(FieldErrorsException.class, () -> terms.replace(EVENT, request(eleven)))
                        .getErrors().get("questions"));

        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(UUID.randomUUID());
        }
        assertEquals(EventTermsAndQuestions.TOO_MANY_CATEGORIES,
                assertThrows(FieldErrorsException.class, () -> terms.replace(EVENT, request(
                        question(null, "Club", "TEXT", false, null, null, many))))
                        .getErrors().get("questions[0].categoryIds"));
    }

    @Test
    void replaceThroughTheEvent_isTheOwnersOrASuperAdmins_only() {
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(published(null)));
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of());

        ForbiddenException refused = assertThrows(ForbiddenException.class, () -> service.replaceBookingQuestions(
                UUID.randomUUID(), "ROLE_EVENT_ORGANIZER", EVENT, request()));
        assertEquals("You are not authorized to update this event", refused.getMessage());

        assertEquals(EVENT, service.replaceBookingQuestions(ORGANIZER, "ROLE_EVENT_ORGANIZER", EVENT, request())
                .getEventId());
        assertEquals(EVENT, service.replaceBookingQuestions(null, "ROLE_SUPER_ADMIN", EVENT, request())
                .getEventId());
    }

    @Test
    void detailAndSalesWindow_carryTheTermsAndQuestions_listsDoNot() {
        Event event = published(2);
        when(eventRepo.findByEventIdAndDeletedFalse(EVENT)).thenReturn(Optional.of(event));
        when(termsRepo.findByEventIdAndVersion(EVENT, 2)).thenReturn(Optional.of(stored(2, "Rules.")));
        EventBookingQuestion q = row(UUID.randomUUID(), 0, "T-shirt size", BookingQuestionType.CHOICE);
        when(questionRepo.findByEventIdOrderByPositionAsc(EVENT)).thenReturn(List.of(q));

        EventResponseDTO detail = service.getEventById(EVENT);
        assertEquals(2, detail.getTermsVersion());
        assertEquals("Rules.", detail.getTermsText());
        assertEquals(1, detail.getBookingQuestions().size());
        assertEquals("T-shirt size", detail.getBookingQuestions().get(0).getLabel());

        SalesWindowDTO window = service.salesWindow(EVENT);
        assertEquals(2, window.getTermsVersion());
        assertEquals(List.of(q.getId()), window.getBookingQuestions().stream().map(BookingQuestionDTO::getId).toList());

        EventResponseDTO listRow = new EventMapper().toDTO(event);
        assertEquals(2, listRow.getTermsVersion(), "lists carry the version");
        assertNull(listRow.getTermsText(), "but not the text");
        assertNull(listRow.getBookingQuestions(), "nor the questions");
    }

    @Test
    void theListConverter_roundTripsAndReadsBlankAsEmpty() {
        JsonStringListConverter converter = new JsonStringListConverter();
        assertEquals("[\"S\",\"M \\\"slim\\\"\"]", converter.convertToDatabaseColumn(List.of("S", "M \"slim\"")));
        assertEquals(List.of("S", "M \"slim\""), converter.convertToEntityAttribute("[\"S\",\"M \\\"slim\\\"\"]"));
        assertEquals("[]", converter.convertToDatabaseColumn(null));
        assertEquals(List.of(), converter.convertToEntityAttribute(""));
    }

    // ---- fixtures ------------------------------------------------------------------

    private static BookingQuestionsRequestDTO request(BookingQuestionDTO... questions) {
        BookingQuestionsRequestDTO r = new BookingQuestionsRequestDTO();
        r.setQuestions(List.of(questions));
        return r;
    }

    private static BookingQuestionDTO question(UUID id, String label, String type, boolean required,
                                               List<String> options, Integer maxLength, List<UUID> categoryIds) {
        return question(id, label, type, required, options, maxLength, categoryIds, null);
    }

    private static BookingQuestionDTO question(UUID id, String label, String type, boolean required,
                                               List<String> options, Integer maxLength, List<UUID> categoryIds,
                                               String helpText) {
        return BookingQuestionDTO.builder().id(id).label(label).type(type).required(required)
                .options(options).maxLength(maxLength).categoryIds(categoryIds).helpText(helpText).build();
    }

    private static EventBookingQuestion row(UUID id, int position, String label, BookingQuestionType type) {
        return EventBookingQuestion.builder().id(id).eventId(EVENT).position(position).label(label).type(type)
                .options(new ArrayList<>(type == BookingQuestionType.CHOICE ? List.of("S", "M") : List.of()))
                .categoryIds(new ArrayList<>())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private static EventTermsVersion stored(int version, String text) {
        return EventTermsVersion.builder().id(UUID.randomUUID()).eventId(EVENT).version(version).text(text)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private static UpdateEventRequestDTO update(String field, String value) {
        UpdateEventRequestDTO u = new UpdateEventRequestDTO();
        if ("termsText".equals(field)) {
            u.setTermsText(value);
        } else {
            u.setTitle(value);
        }
        return u;
    }

    private static CreateEventRequestDTO createRequest() {
        CreateEventRequestDTO req = new CreateEventRequestDTO();
        req.setTitle("Harare Half Marathon");
        req.setVenue("Harare Gardens");
        req.setCategory(EventCategory.CONCERT);
        req.setStartDateTime(LocalDateTime.of(2099, 12, 5, 6, 0));
        req.setEndDateTime(LocalDateTime.of(2099, 12, 5, 12, 0));
        req.setTotalCapacity(1500);
        return req;
    }

    private static Event published(Integer termsVersion) {
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        return Event.builder()
                .eventId(EVENT)
                .tenantUserUuid(ORGANIZER)
                .title("Harare Half Marathon")
                .venue("Harare Gardens")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(end.minusHours(6))
                .endDateTime(end)
                .termsVersion(termsVersion)
                .totalCapacity(1500)
                .availableTickets(1500)
                .deleted(false)
                .active(true)
                .build();
    }
}
