package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTicketDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.TicketWindow;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.client.SeatServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code BookingService#getPublicTicketsByPhoneNumber} — the customer's ticket
 * wallet behind the public {@code GET /bookings/public/phone/{n}}.
 *
 * <p>Pure Mockito, no {@code @SpringBootTest}: these sandboxes have no Docker
 * daemon for Testcontainers (CLAUDE.md), and the behaviour under test is
 * mapping + filtering + fan-out control, none of which needs a database.
 */
class PublicTicketWalletTest {

    private static final String PHONE = "+263771234567";
    private static final UUID FESTIVAL = UUID.randomUUID();
    private static final UUID CONCERT = UUID.randomUUID();

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final EventServiceClient eventClient = mock(EventServiceClient.class);

    @SuppressWarnings("unchecked")
    private BookingService serviceWithEvents() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);
        return new BookingService(bookingRepo, mock(BookingItemRepository.class),
                mock(CategoryInventoryRepository.class), mock(SeatServiceClient.class),
                mock(ApplicationEventPublisher.class), new QrCodeGenerator(),
                null, provider, null, mock(PlatformTransactionManager.class));
    }

    private static Booking booking(UUID eventId, Booking.BookingStatus status, String ticketNumber) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .phoneNumber(PHONE)
                .userEmail("alice@example.com")
                .confirmationNumber("INN-20260502-AB12CD")
                .status(status)
                .totalAmount(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        b.setItems(List.of(BookingItem.builder()
                .seatId(UUID.randomUUID())
                .categoryName("VIP")
                .priceAtBooking(new BigDecimal("100.00"))
                .ticketNumber(ticketNumber)
                .build()));
        return b;
    }

    private void stubEvent(UUID id, String title, LocalDateTime start, LocalDateTime end) {
        when(eventClient.getEventInternal(eq(id), any())).thenReturn(
                ApiResult.ok("ok", EventLookupDTO.builder()
                        .eventId(id).title(title).venue("HICC")
                        .startDateTime(start).endDateTime(end)
                        .build()));
    }

    @Test
    @DisplayName("only CONFIRMED bookings reach the wallet — never PENDING or CANCELLED")
    void excludesUnpaidAndCancelled() {
        LocalDateTime soon = LocalDateTime.now(ZoneOffset.UTC).plusDays(10);
        stubEvent(FESTIVAL, "Jazz Festival", soon, soon.plusHours(6));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-PAID"),
                booking(FESTIVAL, Booking.BookingStatus.PENDING, "T-UNPAID"),
                booking(FESTIVAL, Booking.BookingStatus.CANCELLED, "T-CANCELLED")));

        List<CustomerTicketDTO> tickets = serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null);

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).getItems().get(0).getTicketNumber()).isEqualTo("T-PAID");
    }

    @Test
    @DisplayName("the response is PII-free — no email or phone leaves this endpoint")
    void carriesNoPii() {
        // The endpoint is unauthenticated and enumerable by phone number, so the
        // DTO's omissions are a security control, not a style choice. Assert the
        // fields are genuinely absent rather than trusting the mapper.
        LocalDateTime soon = LocalDateTime.now(ZoneOffset.UTC).plusDays(3);
        stubEvent(FESTIVAL, "Jazz Festival", soon, soon.plusHours(4));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE))
                .thenReturn(List.of(booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-1")));

        CustomerTicketDTO ticket = serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null).get(0);

        assertThat(java.util.Arrays.stream(CustomerTicketDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("userEmail", "phoneNumber", "tenantUserUuid");
        // What it MUST carry: the event context and the scannable QR.
        assertThat(ticket.getEventTitle()).isEqualTo("Jazz Festival");
        assertThat(ticket.getVenue()).isEqualTo("HICC");
        assertThat(ticket.getItems().get(0).getQrCode()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("FAN-OUT: one event lookup per DISTINCT event, not per booking")
    void batchesEventLookups() {
        // Four tickets to the same festival must not cost four cross-service
        // calls; a customer with a big order would otherwise hammer event-service
        // on every wallet open.
        LocalDateTime soon = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
        stubEvent(FESTIVAL, "Jazz Festival", soon, soon.plusHours(6));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-1"),
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-2"),
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-3"),
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-4")));

        assertThat(serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null)).hasSize(4);
        verify(eventClient, times(1)).getEventInternal(eq(FESTIVAL), any());
    }

    @Test
    @DisplayName("FAN-OUT: a FAILED lookup is memoized too — an outage costs one call, not one per row")
    void memoizesFailedLookups() {
        // computeIfAbsent does not cache a null mapping; if this regresses to it,
        // an event-service outage turns every wallet open into a retry storm
        // against a service already in trouble.
        when(eventClient.getEventInternal(eq(FESTIVAL), any()))
                .thenThrow(new RuntimeException("event-service down"));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-1"),
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-2"),
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-3")));

        List<CustomerTicketDTO> tickets = serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null);

        verify(eventClient, times(1)).getEventInternal(eq(FESTIVAL), any());
        // And the paid tickets still come back, visible, with their QR codes.
        assertThat(tickets).hasSize(3);
        assertThat(tickets).allSatisfy(t -> {
            assertThat(t.getWindow()).isEqualTo(TicketWindow.UPCOMING);
            assertThat(t.getItems().get(0).getQrCode()).startsWith("data:image/png;base64,");
        });
    }

    @Test
    @DisplayName("FILTER: each bucket returns only its own events")
    void filtersByWindow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        stubEvent(FESTIVAL, "Next month", now.plusDays(30), now.plusDays(30).plusHours(5));
        stubEvent(CONCERT, "Last year", now.minusDays(365), now.minusDays(365).plusHours(5));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(
                booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-FUTURE"),
                booking(CONCERT, Booking.BookingStatus.CONFIRMED, "T-PAST")));
        BookingService service = serviceWithEvents();

        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, TicketWindow.UPCOMING))
                .extracting(CustomerTicketDTO::getEventTitle).containsExactly("Next month");
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, TicketWindow.PAST))
                .extracting(CustomerTicketDTO::getEventTitle).containsExactly("Last year");
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, TicketWindow.LIVE)).isEmpty();
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, null))
                .as("no filter returns every bucket").hasSize(2);
    }

    @Test
    @DisplayName("ORDER: live first, then upcoming soonest-first, then past most-recent-first")
    void ordersByWhatTheCustomerNeedsNow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID live = UUID.randomUUID();
        UUID soon = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID recentPast = UUID.randomUUID();
        UUID oldPast = UUID.randomUUID();
        stubEvent(live, "Happening now", now.minusHours(1), now.plusHours(1));
        stubEvent(soon, "Next week", now.plusDays(7), now.plusDays(7).plusHours(3));
        stubEvent(later, "Next year", now.plusDays(300), now.plusDays(300).plusHours(3));
        stubEvent(recentPast, "Last month", now.minusDays(30), now.minusDays(30).plusHours(3));
        stubEvent(oldPast, "Two years ago", now.minusDays(700), now.minusDays(700).plusHours(3));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(
                booking(oldPast, Booking.BookingStatus.CONFIRMED, "T-1"),
                booking(later, Booking.BookingStatus.CONFIRMED, "T-2"),
                booking(recentPast, Booking.BookingStatus.CONFIRMED, "T-3"),
                booking(live, Booking.BookingStatus.CONFIRMED, "T-4"),
                booking(soon, Booking.BookingStatus.CONFIRMED, "T-5")));

        assertThat(serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null))
                .extracting(CustomerTicketDTO::getEventTitle)
                .containsExactly("Happening now", "Next week", "Next year",
                        "Last month", "Two years ago");
    }

    @Test
    @DisplayName("an unknown number returns an empty list, never an error")
    void unknownNumberIsEmpty() {
        // A 404-vs-200 split would itself confirm whether a given number has ever
        // bought a ticket — an oracle on an already-enumerable endpoint.
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc("+263700000000")).thenReturn(List.of());
        assertThat(serviceWithEvents().getPublicTicketsByPhoneNumber("+263700000000", null)).isEmpty();
    }

    @Test
    @DisplayName("live is a plain mirror of window == LIVE")
    void liveFlagMirrorsWindow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        stubEvent(FESTIVAL, "Happening now", now.minusMinutes(30), now.plusHours(2));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE))
                .thenReturn(List.of(booking(FESTIVAL, Booking.BookingStatus.CONFIRMED, "T-1")));

        CustomerTicketDTO ticket = serviceWithEvents().getPublicTicketsByPhoneNumber(PHONE, null).get(0);
        assertThat(ticket.getWindow()).isEqualTo(TicketWindow.LIVE);
        assertThat(ticket.isLive()).isTrue();
    }
}
