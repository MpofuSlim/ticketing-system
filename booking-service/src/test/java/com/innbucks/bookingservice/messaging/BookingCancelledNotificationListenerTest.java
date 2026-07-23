package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the cancellation-notice contract: a plain-text email + WhatsApp text go
 * out on BookingCancelled, with refund wording iff the booking was a reversed
 * CONFIRMED booking (availabilityReleased=true). Each channel is independent
 * best-effort.
 */
class BookingCancelledNotificationListenerTest {

    private record Mocks(BookingRepository repo, EmailNotificationClient email,
                         WhatsAppNotificationClient wa, BookingCancelledNotificationListener listener) {}

    private static Mocks mocks() {
        BookingRepository repo = mock(BookingRepository.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        return new Mocks(repo, email, wa, new BookingCancelledNotificationListener(repo, email, wa));
    }

    private static Booking booking(String phone, String emailAddr, boolean availabilityReleased) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setPhoneNumber(phone);
        b.setUserEmail(emailAddr);
        b.setConfirmationNumber("INN-20260610-A1B2C3");
        b.setAvailabilityReleased(availabilityReleased);
        return b;
    }

    private static BookingDomainEvent.BookingCancelled eventFor(Booking b) {
        return new BookingDomainEvent.BookingCancelled(
                b.getId(), b.getUserEmail(), b.getConfirmationNumber(), Instant.now());
    }

    @Test
    void reversedBooking_sendsRefundWordedEmailAndWhatsApp() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", true); // reversed = refund
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendEmail(eq("rufaro@example.com"), subject.capture(), message.capture(),
                startsWith("CANCEL-"));
        assertThat(subject.getValue()).contains("refund in progress").contains("INN-20260610-A1B2C3");
        assertThat(message.getValue()).contains("refund");
        verify(m.wa()).sendCustomNotification(eq("+263771234567"), contains("refund"));
    }

    @Test
    void pendingCancellation_sendsReleasedWording_noRefundMention() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", false); // never paid
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendEmail(eq("rufaro@example.com"), subject.capture(), message.capture(), anyString());
        assertThat(subject.getValue()).doesNotContain("refund");
        assertThat(message.getValue()).contains("released").doesNotContain("refund");
    }

    @Test
    void noPhone_emailOnly() {
        Mocks m = mocks();
        Booking b = booking(null, "rufaro@example.com", false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        verify(m.email()).sendEmail(eq("rufaro@example.com"), anyString(), anyString(), anyString());
        verifyNoInteractions(m.wa());
    }

    @Test
    void noEmail_whatsAppOnly() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", null, false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        verify(m.wa()).sendCustomNotification(eq("+263771234567"), anyString());
        verifyNoInteractions(m.email());
    }

    @Test
    void emailFailure_doesNotBlockWhatsApp() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", true);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(m.email()).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingCancelled(eventFor(b))).doesNotThrowAnyException();
        verify(m.wa()).sendCustomNotification(eq("+263771234567"), anyString());
    }

    @Test
    void bookingMissing_noChannelsTouched() {
        Mocks m = mocks();
        UUID id = UUID.randomUUID();
        when(m.repo().findById(id)).thenReturn(Optional.empty());

        m.listener().onBookingCancelled(new BookingDomainEvent.BookingCancelled(
                id, "gone@example.com", "INN-MISSING", Instant.now()));

        verifyNoInteractions(m.email(), m.wa());
    }
}
