package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.answers.BookingAnswersCipher;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.report.BookingQuestionReportDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.testsupport.OnSaleEvents;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The booking-questions report (V32): counts from the stored answers, scoped like every organizer report. */
class BookingQuestionReportServiceTest {

    private static final UUID SIZE = UUID.randomUUID();
    private static final UUID CONTACT = UUID.randomUUID();
    private static final UUID NEWS = UUID.randomUUID();

    private final UUID eventId = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingAnswersCipher cipher = new BookingAnswersCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final BookingQuestionReportService report;

    BookingQuestionReportServiceTest() {
        BookingService bookings = new BookingService(bookingRepo, itemRepo, mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class), mock(ApplicationEventPublisher.class), new QrCodeGenerator(),
                OnSaleEvents.provider(), mock(PlatformTransactionManager.class));
        bookings.setAnswersCipher(cipher);
        report = new BookingQuestionReportService(itemRepo, bookingRepo, bookings);
    }

    private BookingItem ticket(Booking booking, String size, String contact, String news, String sizeLabel) {
        StringBuilder json = new StringBuilder("[");
        if (size != null) {
            json.append(answer(SIZE, sizeLabel, "CHOICE", size)).append(',');
        }
        if (contact != null) {
            json.append(answer(CONTACT, "Emergency contact", "TEXT", contact)).append(',');
        }
        if (news != null) {
            json.append(answer(NEWS, "Send me news", "CHECKBOX", news)).append(',');
        }
        if (json.length() > 1) {
            json.setLength(json.length() - 1);
        }
        json.append(']');
        BookingItem item = BookingItem.builder().id(UUID.randomUUID())
                .answersCiphertext(json.length() > 2 ? cipher.encrypt(json.toString()) : null).build();
        item.setBooking(booking);
        return item;
    }

    private static String answer(UUID id, String label, String type, String value) {
        return "{\"questionId\":\"" + id + "\",\"label\":\"" + label + "\",\"type\":\"" + type
                + "\",\"value\":\"" + value + "\"}";
    }

    private Booking booking(Integer termsVersion) {
        return Booking.builder().id(UUID.randomUUID()).eventId(eventId).status(Booking.BookingStatus.CONFIRMED)
                .termsVersion(termsVersion).items(new ArrayList<>()).build();
    }

    @Test
    void countsChoicesAndTicks_answeredForText_andBookingsPerTermsVersion() {
        Booking newest = booking(2);
        Booking older = booking(2);
        Booking oldest = booking(1);
        Booking noTerms = booking(null);
        List<BookingItem> items = List.of(
                ticket(newest, "M", "Chipo", "true", "Shirt size"),
                ticket(newest, "M", "Tendai", "false", "Shirt size"),
                ticket(older, "L", "Rudo", null, "T-shirt size"),
                ticket(oldest, "S", null, "true", "T-shirt size"),
                ticket(noTerms, null, null, null, null));
        when(itemRepo.findConfirmedWithBookingByEventIdAndOrganizer(eventId, organizer)).thenReturn(items);

        BookingQuestionReportDTO r = report.report(organizer, eventId);

        assertEquals(5, r.getConfirmedTickets());
        assertFalse(r.isAnswersDeleted());
        assertEquals(List.of(SIZE, CONTACT, NEWS), r.getQuestions().stream()
                .map(BookingQuestionReportDTO.Question::getQuestionId).toList());

        BookingQuestionReportDTO.Question size = r.getQuestions().get(0);
        assertEquals("Shirt size", size.getLabel(), "the latest label it was answered under");
        assertEquals(4, size.getAnswered());
        assertEquals(List.of("M", "L", "S"), size.getOptions().stream()
                .map(BookingQuestionReportDTO.OptionCount::getValue).toList(), "most picked first");
        assertEquals(2, size.getOptions().get(0).getCount());
        assertNull(size.getYes());

        BookingQuestionReportDTO.Question contact = r.getQuestions().get(1);
        assertEquals(3, contact.getAnswered());
        assertEquals(List.of(), contact.getOptions(), "text values are never summarised");

        BookingQuestionReportDTO.Question news = r.getQuestions().get(2);
        assertEquals(2, news.getYes());
        assertEquals(1, news.getNo());

        assertEquals(2, r.getTermsAccepted().size());
        assertEquals(2, r.getTermsAccepted().get(0).getVersion(), "newest version first");
        assertEquals(2, r.getTermsAccepted().get(0).getBookings(), "bookings, not tickets");
        assertEquals(1, r.getTermsAccepted().get(1).getBookings());
        verify(itemRepo, never()).findConfirmedWithBookingByEventId(any());
    }

    @Test
    void platformStaff_readEveryBooking_andDeletedAnswersAreSaidSo() {
        Booking b = booking(1);
        when(itemRepo.findConfirmedWithBookingByEventId(eventId)).thenReturn(List.of(ticket(b, null, null, null, null)));
        when(bookingRepo.anyAnswersPurged(eventId)).thenReturn(true);

        BookingQuestionReportDTO r = report.report(null, eventId);

        assertTrue(r.isAnswersDeleted());
        assertEquals(List.of(), r.getQuestions());
        assertEquals(1, r.getTermsAccepted().get(0).getBookings(), "the consent record stays");
    }

    @Test
    void anEventWithNoConfirmedTickets_isAllZeros() {
        BookingQuestionReportDTO r = report.report(organizer, eventId);
        assertEquals(0, r.getConfirmedTickets());
        assertFalse(r.isAnswersDeleted());
        assertEquals(List.of(), r.getQuestions());
        assertEquals(List.of(), r.getTermsAccepted());
    }
}
