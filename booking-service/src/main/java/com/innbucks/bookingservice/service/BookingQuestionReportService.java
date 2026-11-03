package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.BookingAnswerDTO;
import com.innbucks.bookingservice.dto.report.BookingQuestionReportDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code GET /event-organizer/reports/booking-questions} (V32): how one event's
 * confirmed tickets answered its booking questions, and which terms versions
 * their bookings accepted. An organizer reads only their own event's bookings
 * (an event that is not theirs is all zeros, like the other organizer
 * reports); platform staff read any.
 *
 * <p>Built from the stored answers alone, so a question removed from the event
 * still shows while tickets hold answers to it, under the label it was last
 * answered with. Each answer is opened in memory; an event's tickets are
 * thousands at most.
 */
@Service
@RequiredArgsConstructor
public class BookingQuestionReportService {

    private final BookingItemRepository bookingItemRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    @Transactional(readOnly = true)
    public BookingQuestionReportDTO report(UUID organizerUuid, UUID eventId) {
        // Newest booking first, so the first label seen for a question is the latest.
        List<BookingItem> items = organizerUuid == null
                ? bookingItemRepository.findConfirmedWithBookingByEventId(eventId)
                : bookingItemRepository.findConfirmedWithBookingByEventIdAndOrganizer(eventId, organizerUuid);

        Map<UUID, Tally> tallies = new LinkedHashMap<>();
        Map<Integer, Set<UUID>> bookingsByTerms = new TreeMap<>(Comparator.reverseOrder());
        for (BookingItem item : items) {
            Booking booking = item.getBooking();
            if (booking.getTermsVersion() != null) {
                bookingsByTerms.computeIfAbsent(booking.getTermsVersion(), v -> new HashSet<>()).add(booking.getId());
            }
            for (BookingAnswerDTO answer : bookingService.openAnswers(item)) {
                tallies.computeIfAbsent(answer.getQuestionId(), id -> new Tally(answer.getLabel(), answer.getType()))
                        .add(answer.getValue());
            }
        }

        List<BookingQuestionReportDTO.Question> questions = new ArrayList<>();
        tallies.forEach((id, t) -> questions.add(t.toQuestion(id)));
        List<BookingQuestionReportDTO.TermsCount> terms = new ArrayList<>();
        bookingsByTerms.forEach((version, bookings) -> terms.add(
                BookingQuestionReportDTO.TermsCount.builder().version(version).bookings(bookings.size()).build()));

        boolean deleted = questions.isEmpty() && !items.isEmpty() && bookingRepository.anyAnswersPurged(eventId);
        return BookingQuestionReportDTO.builder()
                .eventId(eventId)
                .confirmedTickets(items.size())
                .answersDeleted(deleted)
                .questions(questions)
                .termsAccepted(terms)
                .build();
    }

    private static final class Tally {
        private final String label;
        private final String type;
        private long answered;
        private final Map<String, Long> values = new HashMap<>();

        Tally(String label, String type) {
            this.label = label;
            this.type = type;
        }

        void add(String value) {
            answered++;
            if ("CHOICE".equals(type) || "CHECKBOX".equals(type)) {
                values.merge(value, 1L, Long::sum);
            }
        }

        BookingQuestionReportDTO.Question toQuestion(UUID id) {
            boolean checkbox = "CHECKBOX".equals(type);
            List<BookingQuestionReportDTO.OptionCount> options = new ArrayList<>();
            if ("CHOICE".equals(type)) {
                values.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .forEach(e -> options.add(BookingQuestionReportDTO.OptionCount.builder()
                                .value(e.getKey()).count(e.getValue()).build()));
            }
            return BookingQuestionReportDTO.Question.builder()
                    .questionId(id)
                    .label(label)
                    .type(type)
                    .answered(answered)
                    .options(options)
                    .yes(checkbox ? values.getOrDefault("true", 0L) : null)
                    .no(checkbox ? values.getOrDefault("false", 0L) : null)
                    .build();
        }
    }
}
