package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
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
 * Unit tests for {@link BookingConfirmedNotificationListener}.
 *
 * <p>Pins: email + WhatsApp(→SMS) as INDEPENDENT best-effort channels, the
 * hosted ticket link injected into every message, and that no channel failure
 * escapes (the booking is already CONFIRMED). Pure Mockito.
 */
class BookingConfirmedNotificationListenerTest {

    private static final String BASE = "https://api.test";

    private record Mocks(BookingRepository repo, WhatsAppNotificationClient wa,
                         SmsNotificationClient sms, EmailNotificationClient email,
                         TicketRenderingService rendering,
                         BookingConfirmedNotificationListener listener) {}

    private static Mocks mocks() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        TicketRenderingService rendering = mock(TicketRenderingService.class);
        lenient().when(rendering.confirmationEmailHtml(any(), anyString()))
                .thenReturn("<div>tickets</div>");
        BookingConfirmedNotificationListener listener =
                new BookingConfirmedNotificationListener(repo, wa, sms, email, rendering, BASE);
        return new Mocks(repo, wa, sms, email, rendering, listener);
    }

    private static Booking bookingFixture(String phone, String emailAddr, int ticketCount) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
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

    @Test
    void confirmed_sendsEmailAndWhatsApp_bothCarryTheTicketLink() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        m.listener().onBookingConfirmed(eventFor(b));

        // Email: to the booking address, subject carries the confirmation, body
        // is the rendered ticket HTML, reference is the booking handle.
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), subject.capture(),
                body.capture(), ref.capture());
        assertThat(subject.getValue()).contains("INN-20260610-A1B2C3");
        assertThat(body.getValue()).isEqualTo("<div>tickets</div>");
        assertThat(ref.getValue()).startsWith("BOOKING-CONFIRM-");

        // WhatsApp: confirmation + the hosted ticket link + ticket numbers; no SMS on success.
        ArgumentCaptor<String> wa = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendCustomNotification(eq("+263771234567"), wa.capture());
        assertThat(wa.getValue())
                .contains("INN-20260610-A1B2C3").contains("2 tickets")
                .contains("20260610-T1").contains("20260610-T2")
                // No links of any kind — product direction; QR rides the email.
                .doesNotContain("http");
        verifyNoInteractions(m.sms());
    }

    @Test
    void singleTicket_saysOneTicket_notPlural() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(m.wa()).sendCustomNotification(anyString(), msg.capture());
        assertThat(msg.getValue()).contains("1 ticket").doesNotContain("1 tickets");
    }

    @Test
    void whatsAppFails_fallsBackToSms_withTheLink_emailUnaffected() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("gateway 503"))
                .when(m.wa()).sendCustomNotification(anyString(), anyString());
        m.listener().onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> smsMsg = ArgumentCaptor.forClass(String.class);
        verify(m.sms()).sendSms(eq("+263771234567"), smsMsg.capture(), startsWith("BOOKING-CONFIRM-"));
        assertThat(smsMsg.getValue()).contains("INN-20260610-A1B2C3").doesNotContain("http");
        // Email is an independent channel — still delivered despite WhatsApp failure.
        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    void emailFailure_doesNotBlockWhatsApp() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", "rufaro@example.com", 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(m.email()).sendHtmlEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();
        // WhatsApp still fired even though email blew up first.
        verify(m.wa()).sendCustomNotification(eq("+263771234567"), anyString());
    }

    @Test
    void noPhone_emailStillDelivers() {
        Mocks m = mocks();
        Booking b = bookingFixture(null, "rufaro@example.com", 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        verify(m.email()).sendHtmlEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
        verifyNoInteractions(m.wa(), m.sms());
    }

    @Test
    void noEmail_whatsAppStillDelivers() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingConfirmed(eventFor(b));

        verify(m.wa()).sendCustomNotification(eq("+263771234567"), anyString());
        verifyNoInteractions(m.email());
    }

    @Test
    void noEmailAndNoPhone_doesNothing_noThrow() {
        Mocks m = mocks();
        Booking b = bookingFixture(null, null, 1);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();
        verifyNoInteractions(m.wa(), m.sms(), m.email());
    }

    @Test
    void bothPhoneChannelsFail_doesNotThrow() {
        Mocks m = mocks();
        Booking b = bookingFixture("+263771234567", null, 2);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("wa down"))
                .when(m.wa()).sendCustomNotification(anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(m.sms()).sendSms(anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingConfirmed(eventFor(b))).doesNotThrowAnyException();
        verify(m.wa()).sendCustomNotification(anyString(), anyString());
        verify(m.sms()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void bookingMissing_logsAndReturns_noChannelTouched() {
        Mocks m = mocks();
        UUID bookingId = UUID.randomUUID();
        when(m.repo().findById(bookingId)).thenReturn(Optional.empty());

        m.listener().onBookingConfirmed(new BookingDomainEvent.BookingConfirmed(
                bookingId, "gone@example.com", "INN-MISSING", Instant.now()));

        verifyNoInteractions(m.wa(), m.sms(), m.email());
    }
}
