package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.answers.BookingAnswersCipher;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingAnswerDTO;
import com.innbucks.bookingservice.dto.BookingQuestionDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.testsupport.OnSaleEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * What POST /bookings records of the terms and answers (V32), and where the
 * answers are shown: the buyer's, the organizer's and staff's reads only.
 */
class BookingTermsAndAnswersTest {

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID SIZE = UUID.randomUUID();
    private static final LocalDateTime EVENT_END = LocalDateTime.of(2026, 12, 5, 10, 0);

    private final UUID eventId = UUID.randomUUID();
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final SeatServiceClient seats = mock(SeatServiceClient.class);
    private final EventServiceClient events = mock(EventServiceClient.class);
    private final BookingAnswersCipher cipher =
            new BookingAnswersCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private BookingService service() {
        CategoryInventoryRepository inventory = mock(CategoryInventoryRepository.class);
        when(inventory.seedIfAbsent(any(), anyInt())).thenReturn(1);
        when(inventory.tryClaim(any(), anyInt())).thenReturn(1);
        when(seats.getCategory(CATEGORY)).thenReturn(ApiResult.ok("ok", CategoryLookupDTO.builder()
                .seatCategoryId(CATEGORY).eventId(eventId).name("21 km").price(new BigDecimal("30.00"))
                .totalSeats(100).availableSeats(100).build()));
        BookingService service = new BookingService(bookingRepo, itemRepo, inventory, seats,
                mock(ApplicationEventPublisher.class), new QrCodeGenerator(), OnSaleEvents.providerFor(events),
                mock(PlatformTransactionManager.class));
        service.setAnswersCipher(cipher);
        return service;
    }

    private void eventAsks(Integer termsVersion, BookingQuestionDTO... questions) {
        when(events.salesWindow(any(), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .endDateTime(EVENT_END).termsVersion(termsVersion)
                        .bookingQuestions(List.of(questions)).build()));
    }

    private static BookingQuestionDTO size() {
        return BookingQuestionDTO.builder().id(SIZE).label("T-shirt size").type("CHOICE").required(true)
                .options(List.of("S", "M", "L")).categoryIds(List.of()).build();
    }

    private CreateBookingRequestDTO request(Integer acceptedTerms, String sizeAnswer) {
        CreateBookingRequestDTO r = new CreateBookingRequestDTO();
        r.setEventId(eventId);
        r.setCustomerName("Rufaro Moyo");
        r.setAcceptedTermsVersion(acceptedTerms);
        CreateBookingRequestDTO.SeatItemRequest seat = new CreateBookingRequestDTO.SeatItemRequest();
        seat.setCategoryId(CATEGORY);
        if (sizeAnswer != null) {
            CreateBookingRequestDTO.AnswerRequest a = new CreateBookingRequestDTO.AnswerRequest();
            a.setQuestionId(SIZE);
            a.setValue(sizeAnswer);
            seat.setAnswers(List.of(a));
        }
        r.setSeats(List.of(seat));
        return r;
    }

    @Test
    void aBooking_recordsTheAcceptedTerms_sealsTheAnswers_andDatesTheirDeletion() {
        eventAsks(2, size());

        BookingResponseDTO created = service().createBooking(null, "+263771234567", request(2, "m"));

        ArgumentCaptor<Booking> booking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(booking.capture());
        Booking saved = booking.getValue();
        assertEquals(2, saved.getTermsVersion());
        assertNotNull(saved.getTermsAcceptedAt());
        assertEquals(EVENT_END.plusDays(90), saved.getAnswersPurgeAfter());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BookingItem>> items = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAllAndFlush(items.capture());
        String sealed = items.getValue().get(0).getAnswersCiphertext();
        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains("T-shirt"), "never plaintext in the column");
        assertTrue(cipher.decrypt(sealed).contains("\"value\":\"M\""));

        assertEquals(2, created.getTermsVersion());
        List<BookingAnswerDTO> answers = created.getItems().get(0).getAnswers();
        assertEquals(1, answers.size());
        assertEquals("M", answers.get(0).getValue());
        assertEquals("T-shirt size", answers.get(0).getLabel());
    }

    @Test
    void aBookingWithoutAnswers_storesNoneAndSchedulesNoDeletion() {
        eventAsks(null);

        BookingResponseDTO created = service().createBooking(null, "+263771234567", request(null, null));

        ArgumentCaptor<Booking> booking = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepo, atLeastOnce()).save(booking.capture());
        assertNull(booking.getValue().getTermsVersion());
        assertNull(booking.getValue().getTermsAcceptedAt());
        assertNull(booking.getValue().getAnswersPurgeAfter());
        assertEquals(List.of(), created.getItems().get(0).getAnswers());
    }

    @Test
    void refusedTermsOrAnswers_holdNothing() {
        eventAsks(2, size());
        BookingService service = service();

        assertEquals("TERMS_NOT_ACCEPTED", assertThrows(ApiProblemException.class,
                () -> service.createBooking(null, "+263771234567", request(null, "M"))).getErrorCode());
        assertEquals("ANSWERS_INVALID", assertThrows(ApiProblemException.class,
                () -> service.createBooking(null, "+263771234567", request(2, "XXL"))).getErrorCode());

        verifyNoInteractions(seats);
        verify(bookingRepo, never()).save(any());
        verify(itemRepo, never()).saveAllAndFlush(anyList());
    }

    @Test
    void answersAreOnTheBuyersAndStaffReads_neverOnTheServiceRead() {
        BookingService service = service();
        UUID id = UUID.randomUUID();
        BookingItem item = BookingItem.builder().id(UUID.randomUUID()).seatId(UUID.randomUUID())
                .categoryId(CATEGORY).categoryName("21 km").priceAtBooking(new BigDecimal("30.00"))
                .ticketNumber("20261120-7KQ4M-9XZ2B")
                .answersCiphertext(cipher.encrypt(
                        "[{\"questionId\":\"" + SIZE + "\",\"label\":\"T-shirt size\",\"type\":\"CHOICE\",\"value\":\"L\"}]"))
                .build();
        Booking booking = Booking.builder().id(id).eventId(eventId).userEmail("buyer@example.com")
                .status(Booking.BookingStatus.CONFIRMED).totalAmount(new BigDecimal("30.00"))
                .termsVersion(2).termsAcceptedAt(LocalDateTime.now(ZoneOffset.UTC))
                .items(new ArrayList<>(List.of(item))).build();
        item.setBooking(booking);
        when(bookingRepo.findByIdWithItems(id)).thenReturn(Optional.of(booking));

        BookingResponseDTO buyer = service.getBookingById(id, "buyer@example.com");
        assertEquals("L", buyer.getItems().get(0).getAnswers().get(0).getValue());
        assertEquals(2, buyer.getTermsVersion());

        BookingResponseDTO staff = service.getBookingById(id, "admin@innbucks.co.zw", true);
        assertEquals("L", staff.getItems().get(0).getAnswers().get(0).getValue());

        assertNull(service.getBookingForService(id).getItems().get(0).getAnswers(),
                "no answers on the S2S read: the key is left out");
    }

    @Test
    void answersThatCannotBeOpened_readAsNone_neverAFailedRead() {
        BookingService service = service();
        BookingItem item = BookingItem.builder().id(UUID.randomUUID())
                .answersCiphertext(new BookingAnswersCipher("").encrypt("[]")).build();
        assertEquals(List.of(), service.openAnswers(item));
        assertEquals(List.of(), service.openAnswers(BookingItem.builder().build()));
    }
}
