package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.service.TicketRenderingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the listener's single-WhatsApp-channel contract: per-ticket QR sends
 * via the gateway's {@code /api/messages/event-qr-code} endpoint, with the
 * event title + booking-confirmation summary + brand sign-off concatenated
 * into the Twilio template's {@code eventName} variable. There is NO
 * {@code /api/messages/send} (custom-notification) call, and NO SMS fallback —
 * email is the only secondary channel.
 */
class BookingConfirmedNotificationListenerTest {

    private static final String BASE = "https://api.test";

    private record Mocks(BookingRepository repo, WhatsAppNotificationClient wa,
                         EmailNotificationClient email,
                         TicketRenderingService rendering, EventServiceClient events,
                         BookingConfirmedNotificationListener listener) {}

    private static Mocks mocks() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        TicketRenderingService rendering = mock(TicketRenderingService.class);
        EventServiceClient events = mock(EventServiceClient.class);
        lenient().when(rendering.confirmationEmailHtml(any(), anyString()))
                .thenReturn("<div>tickets</div>");
        // Default event lookup resolves a title; tests needing the fallback override this.
        lenient().when(events.getEvent(any()))
                .thenReturn(ApiResult.ok(EventLookupDTO.builder().title("InnBucks Gala 2026").build()));
        BookingConfirmedNotificationListener listener =
                new BookingConfirmedNotificationListener(repo, wa, email, rendering, events, BASE);
        return new Mocks(repo, wa, email, rendering, events, listener);
    }

    private static Booking bookingFixture(String phone, String emailAddr, int ticketCount) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setEventId(UUID.randomUUID());
        b.setPhoneNumber(phone);
        b.setUserEmail(emailAddr);
        b.setConfirmationNumber("INN-20260610-A1B2C3");
        b.setTotalAmount(new BigDecimal("50.00"));
        b.setItems(buildItems(ticketCount));
        return b;
    }

    private static List<BookingItem> buildItems(int n) {
        BookingItem[] arr = new BookingItem[n];
        for (int i = 0; i < n; i++) {
            BookingItem item = new BookingItem();
            item.setTicketNumber("20260610-T" + (i + 1));
            arr[i] = item;
        }
        return List.of(arr);
    }

    private static BookingDomainEvent.BookingConfirmed eventFor(Booking b) {
        return new BookingDomainEvent.BookingConfirmed(
                b.getId(), b.getUserEmail(), b.getConfirmationNumber(), Instant.now());
    }

    // ---- Happy path: email + per-ticket QR sends; NO /send call -------------

    @Test
    void confirmed_sendsEmail_andOneQrPerTicket_neverHitsCustomNotificationEndpoint() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        // Email goes out (subject carries the booking ref).
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), subject.capture(),
                eq("<div>tickets</div>"), startsWith("BOOKING-CONFIRM-"));
        assertThat(subject.getValue()).contains("INN-20260610-A1B2C3");

        // One QR send per ticket — and the /custom-notification text endpoint is NEVER touched.
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(m.wa(), times(2)).sendEventQrCode(eq("+263771234567"), anyString(), path.capture());
        assertThat(path.getAllValues()).containsExactlyInAnyOrder(
                "/bookings/" + b.getId() + "/tickets/20260610-T1/qr",
                "/bookings/" + b.getId() + "/tickets/20260610-T2/qr");
        verify(m.wa(), never()).sendCustomNotification(anyString(), anyString());
    }

    // ---- eventName concatenation: title + summary + sign-off ----------------

    @Test
    void eventName_concatenatesEventTitle_bookingSummary_andSignOff() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa(), times(2)).sendEventQrCode(eq("+263771234567"), name.capture(), anyString());
        String eventName = name.getAllValues().get(0);

        // Event title comes from the event-service lookup.
        assertThat(eventName).startsWith("InnBucks Gala 2026\n\n");
        // The booking-confirmation summary (was /api/messages/send body) follows.
        assertThat(eventName)
                .contains("InnBucks: your booking INN-20260610-A1B2C3 is confirmed")
                .contains("2 tickets")
                .contains("total 50.00")
                .contains("20260610-T1")
                .contains("20260610-T2");
        // Brand sign-off at the bottom.
        assertThat(eventName).endsWith("• The InnBucks Team");
        // No URLs leak into the message (product direction — no link).
        assertThat(eventName).doesNotContain("http");
    }

    @Test
    void singleTicket_summarySaysOneTicket_notPlural() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendEventQrCode(anyString(), name.capture(), anyString());
        assertThat(name.getValue()).contains("1 ticket").doesNotContain("1 tickets");
        // Singular "Ticket number:" — no trailing 's'.
        assertThat(name.getValue()).contains("Ticket number:").doesNotContain("Ticket numbers:");
    }

    @Test
    void eventLookupFails_eventNameFallsBackButStillIncludesSummary() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        when(m.events().getEvent(b.getEventId()))
                .thenThrow(new RuntimeException("event-service circuit open"));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendEventQrCode(anyString(), name.capture(), anyString());
        assertThat(name.getValue()).startsWith("your event\n\n");
        assertThat(name.getValue()).contains("INN-20260610-A1B2C3");
    }

    // ---- Best-effort isolation: one failing channel never blocks another ----

    @Test
    void qrETicket_oneTicketFails_theOthersStillSend() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 3);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("media fetch 404"))
                .doNothing()
                .when(m.wa()).sendEventQrCode(anyString(), anyString(),
                        eq("/bookings/" + b.getId() + "/tickets/20260610-T1/qr"));

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();

        // All three were attempted despite the first failing.
        verify(m.wa(), times(3)).sendEventQrCode(eq("+263771234567"), anyString(), anyString());
    }

    @Test
    void emailFailure_doesNotBlockWhatsApp() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(m.email()).sendHtmlEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();
        verify(m.wa()).sendEventQrCode(eq("+263771234567"), anyString(), anyString());
    }

    @Test
    void whatsAppCompletelyDown_doesNotThrow_emailUnaffected() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("gw 503"))
                .when(m.wa()).sendEventQrCode(anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();

        verify(m.wa(), times(2)).sendEventQrCode(anyString(), anyString(), anyString());
        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
    }

    // ---- Channel presence: only the channels with addresses fire -----------

    @Test
    void noPhone_emailStillDelivers_noWhatsAppCalls() {
        Mocks m = mocks();
        Booking b = bookingFixture(null, "rufaro@example.com", 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
        verifyNoInteractions(m.wa());
    }

    @Test
    void noEmail_whatsAppStillDelivers_noEmailCalls() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        verify(m.wa()).sendEventQrCode(eq("+263771234567"), anyString(), anyString());
        verifyNoInteractions(m.email());
    }

    @Test
    void noEmailAndNoPhone_doesNothing_noThrow() {
        Mocks m = mocks();
        Booking b = bookingFixture(null, null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();
        verifyNoInteractions(m.wa(), m.email());
    }

    @Test
    void bookingMissing_logsAndReturns_noChannelTouched() {
        Mocks m = mocks();
        UUID bookingId = UUID.randomUUID();
        when(m.repo().findById(bookingId)).thenReturn(Optional.empty());

        m.listener().onBookingConfirmed(new BookingDomainEvent.BookingConfirmed(
                bookingId, "gone@example.com", "INN-MISSING", Instant.now()));

        verifyNoInteractions(m.wa(), m.email());
    }
}
