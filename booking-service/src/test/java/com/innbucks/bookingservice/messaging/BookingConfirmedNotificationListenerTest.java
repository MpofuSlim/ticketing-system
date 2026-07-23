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
 * Pins the listener's two-channel contract: a plain-text confirmation email via
 * the InnBucks notification API, plus one per-ticket QR send via the WhatsApp
 * gateway's {@code /api/messages/event-qr-code} endpoint (event title + booking
 * summary concatenated into the Twilio template's {@code eventName} variable).
 * There is NO {@code /api/messages/send} call and NO SMS fallback — email is the
 * only secondary channel. Each channel is independent best-effort.
 */
class BookingConfirmedNotificationListenerTest {

    private record Mocks(BookingRepository repo, WhatsAppNotificationClient wa,
                         EmailNotificationClient email, EventServiceClient events,
                         BookingConfirmedNotificationListener listener) {}

    private static Mocks mocks() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        EventServiceClient events = mock(EventServiceClient.class);
        // Default event lookup resolves a title; tests needing the fallback override this.
        lenient().when(events.getEvent(any()))
                .thenReturn(ApiResult.ok(EventLookupDTO.builder().title("InnBucks Gala 2026").build()));
        BookingConfirmedNotificationListener listener =
                new BookingConfirmedNotificationListener(repo,
                        new com.innbucks.bookingservice.service.TicketDeliveryService(wa, email, events));
        return new Mocks(repo, wa, email, events, listener);
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

    // ---- Happy path: plain-text email + per-ticket QR sends; NO /send call ----

    @Test
    void confirmed_sendsPlainTextEmail_andOneQrPerTicket_neverHitsCustomNotificationEndpoint() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        // Plain-text email goes out; subject + body carry the booking ref + ticket summary.
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendEmail(eq("rufaro@example.com"), subject.capture(),
                message.capture(), startsWith("BOOKING-CONFIRM-"));
        assertThat(subject.getValue()).contains("INN-20260610-A1B2C3");
        assertThat(message.getValue())
                .contains("INN-20260610-A1B2C3")
                .contains("Tickets: 2")
                .contains("WhatsApp");

        // One QR send per ticket — and the /custom-notification text endpoint is NEVER touched.
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(m.wa(), times(2)).sendEventQrCode(eq("+263771234567"), anyString(), path.capture());
        assertThat(path.getAllValues()).containsExactlyInAnyOrder(
                "/bookings/" + b.getId() + "/tickets/20260610-T1/qr.png",
                "/bookings/" + b.getId() + "/tickets/20260610-T2/qr.png");
        verify(m.wa(), never()).sendCustomNotification(anyString(), anyString());
    }

    // ---- eventName: single-line noun phrase, NO newlines (WhatsApp 63021) ----

    @Test
    void eventName_isSingleLineNounPhrase_withTitleSummaryAndTicketNumbers() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa(), times(2)).sendEventQrCode(eq("+263771234567"), name.capture(), anyString());
        String eventName = name.getAllValues().get(0);

        // Reads as: "InnBucks Gala 2026 (booking INN-..., 2 tickets, total 50.00 — T1, T2)"
        assertThat(eventName)
                .startsWith("InnBucks Gala 2026 (booking INN-20260610-A1B2C3")
                .contains("2 tickets")
                .contains("total 50.00")
                .contains("20260610-T1")
                .contains("20260610-T2")
                .endsWith(")");
        // CRITICAL: WhatsApp template variables reject newlines / tabs / >4
        // consecutive spaces (Twilio 63021). Guard all three.
        assertThat(eventName)
                .doesNotContain("\n")
                .doesNotContain("\t")
                .doesNotContain("    ")
                .doesNotContain("http");
    }

    @Test
    void eventName_singleTicket_saysOneTicket_notPlural() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendEventQrCode(anyString(), name.capture(), anyString());
        assertThat(name.getValue()).contains("1 ticket").doesNotContain("1 tickets");
        assertThat(name.getValue()).doesNotContain("\n");
    }

    @Test
    void eventName_eventLookupFails_fallsBackButStaysSingleLineWithSummary() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        when(m.events().getEvent(b.getEventId()))
                .thenThrow(new RuntimeException("event-service circuit open"));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendEventQrCode(anyString(), name.capture(), anyString());
        assertThat(name.getValue())
                .startsWith("your event (booking INN-20260610-A1B2C3")
                .doesNotContain("\n");
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
                        eq("/bookings/" + b.getId() + "/tickets/20260610-T1/qr.png"));

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
                .when(m.email()).sendEmail(anyString(), anyString(), anyString(), anyString());

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
        verify(m.email()).sendEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
    }

    // ---- Channel presence: only the channels with addresses fire -----------

    @Test
    void noPhone_emailStillDelivers_noWhatsAppCalls() {
        Mocks m = mocks();
        Booking b = bookingFixture(null, "rufaro@example.com", 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        verify(m.email()).sendEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
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
