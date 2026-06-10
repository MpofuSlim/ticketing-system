package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
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
 * Unit tests for {@link BookingConfirmedNotificationListener}.
 *
 * <p>Pins the WhatsApp-primary → SMS-fallback channel order, best-effort
 * semantics (no exception escapes), and the skip-when-no-phone guard. Pure
 * Mockito — the listener has no Spring context dependencies once the booking
 * is mocked through the repo.
 */
class BookingConfirmedNotificationListenerTest {

    private static Booking bookingFixture(String phone, int ticketCount) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setPhoneNumber(phone);
        b.setUserEmail("rufaro@example.com");
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
    void confirmedBooking_sendsWhatsAppFirstAndSkipsSmsOnSuccess() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        Booking b = bookingFixture("+263771234567", 2);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(wa).sendCustomNotification(eq("+263771234567"), msg.capture());
        // Verify message content carries the data the customer needs to find their booking.
        assertThat(msg.getValue())
                .contains("INN-20260610-A1B2C3")
                .contains("2 tickets")
                .contains("50.00")
                .contains("20260610-T1")
                .contains("20260610-T2");
        // SMS fallback must not fire on a WhatsApp success.
        verifyNoInteractions(sms);
    }

    @Test
    void singleTicket_messageSaysOneTicket_notPlural() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        Booking b = bookingFixture("+263771234567", 1);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(wa).sendCustomNotification(anyString(), msg.capture());
        assertThat(msg.getValue())
                .contains("1 ticket")
                .doesNotContain("1 tickets");
    }

    @Test
    void whatsAppFails_fallsBackToSmsWithShorterMessage() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        Booking b = bookingFixture("+263771234567", 2);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("gateway 503"))
                .when(wa).sendCustomNotification(anyString(), anyString());

        new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(eventFor(b));

        ArgumentCaptor<String> smsMsg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263771234567"), smsMsg.capture(), ref.capture());
        assertThat(smsMsg.getValue()).contains("INN-20260610-A1B2C3").contains("2 tickets");
        // The SMS lane omits the ticket-number dump to stay within a single segment.
        assertThat(smsMsg.getValue())
                .doesNotContain("20260610-T1")
                .doesNotContain("20260610-T2");
        assertThat(ref.getValue()).startsWith("BOOKING-CONFIRM-");
    }

    @Test
    void bothChannelsFail_doesNotThrow_bookingUnaffected() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        Booking b = bookingFixture("+263771234567", 2);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("wa down"))
                .when(wa).sendCustomNotification(anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        // Best-effort: a total outage must not propagate after AFTER_COMMIT.
        assertThatCode(() ->
                new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(eventFor(b)))
                .doesNotThrowAnyException();
        verify(wa).sendCustomNotification(anyString(), anyString());
        verify(sms).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void noPhoneOnBooking_skipsBothChannels() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        Booking b = bookingFixture(null, 1);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(eventFor(b));

        verifyNoInteractions(wa, sms);
    }

    @Test
    void bookingMissing_logsAndReturns_noChannelTouched() {
        BookingRepository repo = mock(BookingRepository.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        UUID bookingId = UUID.randomUUID();
        when(repo.findById(bookingId)).thenReturn(Optional.empty());

        new BookingConfirmedNotificationListener(repo, wa, sms).onBookingConfirmed(
                new BookingDomainEvent.BookingConfirmed(
                        bookingId, "gone@example.com", "INN-MISSING", Instant.now()));

        verifyNoInteractions(wa, sms);
    }
}
