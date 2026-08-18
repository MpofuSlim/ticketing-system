package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.util.SmsTextSanitizer;
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
 * Pins the cancellation-notice contract: a plain-text email plus a phone
 * notice go out on BookingCancelled, with refund wording iff the booking was a
 * reversed CONFIRMED booking (availabilityReleased=true).
 *
 * <p>The phone leg is SMS FIRST and WhatsApp only on SMS failure — never both,
 * since one cancellation should produce one message and each send is billable.
 * Email and phone are independent best-effort legs.
 */
class BookingCancelledNotificationListenerTest {

    private record Mocks(BookingRepository repo, EmailNotificationClient email, SmsNotificationClient sms,
                         WhatsAppNotificationClient wa, BookingCancelledNotificationListener listener) {}

    private static Mocks mocks() {
        BookingRepository repo = mock(BookingRepository.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        return new Mocks(repo, email, sms, wa,
                new BookingCancelledNotificationListener(repo, email, sms, wa));
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
    void reversedBooking_sendsRefundWordedEmailAndSms() {
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
        verify(m.sms()).sendSms(eq("+263771234567"), contains("refund"), startsWith("CANCEL-"));
        verifyNoInteractions(m.wa());
    }

    @Test
    void smsSucceeds_whatsAppNeverAlsoFires() {
        // One cancellation, one message. WhatsApp is a FALLBACK, not a second
        // copy — sending both would double-notify and double-bill.
        Mocks m = mocks();
        Booking b = booking("+263771234567", null, false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        verify(m.sms()).sendSms(eq("+263771234567"), anyString(), anyString());
        verifyNoInteractions(m.wa());
    }

    @Test
    void smsFailure_fallsBackToWhatsApp() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", null, false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("sms gw down"))
                .when(m.sms()).sendSms(anyString(), anyString(), anyString());

        m.listener().onBookingCancelled(eventFor(b));

        verify(m.wa()).sendCustomNotification(eq("+263771234567"), contains("released"));
    }

    @Test
    void bothPhoneChannelsFail_cancellationStillStands() {
        // The booking is already CANCELLED and committed before this listener
        // runs, so no delivery outcome may propagate out of it.
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("sms gw down"))
                .when(m.sms()).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("wa gw down"))
                .when(m.wa()).sendCustomNotification(anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingCancelled(eventFor(b))).doesNotThrowAnyException();
        verify(m.email()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void smsAndEmailShareOneReference() {
        // Both legs of one cancellation must correlate in the notification API's
        // logs, which is the only way to trace "did this customer get told?".
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        ArgumentCaptor<String> emailRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> smsRef = ArgumentCaptor.forClass(String.class);
        verify(m.email()).sendEmail(anyString(), anyString(), anyString(), emailRef.capture());
        verify(m.sms()).sendSms(anyString(), anyString(), smsRef.capture());
        assertThat(smsRef.getValue()).isEqualTo(emailRef.getValue());
        // The notification API caps the reference at 46 characters.
        assertThat(smsRef.getValue()).hasSizeLessThanOrEqualTo(46);
    }

    @Test
    void bothCopyVariantsSurviveTheSmsSanitizerUnchanged() {
        // The notification API rejects  !  :  /  ?  "  *  ;  and SmsTextSanitizer
        // rewrites them, so a question mark or em dash in the copy would reach the
        // customer altered ("Questions?" -> "Questions."). Asserting a clean
        // round-trip fails the build if either creeps back in.
        Mocks m = mocks();
        for (boolean refund : new boolean[] {true, false}) {
            Booking b = booking("+263771234567", null, refund);
            when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

            m.listener().onBookingCancelled(eventFor(b));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(m.sms()).sendSms(eq("+263771234567"), body.capture(), anyString());
            assertThat(SmsTextSanitizer.toGsmSafe(body.getValue()))
                    .as("refund=%s copy must be natively SMS-safe", refund)
                    .isEqualTo(body.getValue());
            reset(m.sms());
        }
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
        verifyNoInteractions(m.sms(), m.wa());
    }

    @Test
    void noEmail_smsOnly() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", null, false);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));

        m.listener().onBookingCancelled(eventFor(b));

        verify(m.sms()).sendSms(eq("+263771234567"), anyString(), anyString());
        verifyNoInteractions(m.email(), m.wa());
    }

    @Test
    void emailFailure_doesNotBlockSms() {
        Mocks m = mocks();
        Booking b = booking("+263771234567", "rufaro@example.com", true);
        when(m.repo().findById(b.getId())).thenReturn(Optional.of(b));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(m.email()).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> m.listener().onBookingCancelled(eventFor(b))).doesNotThrowAnyException();
        verify(m.sms()).sendSms(eq("+263771234567"), anyString(), anyString());
    }

    @Test
    void bookingMissing_noChannelsTouched() {
        Mocks m = mocks();
        UUID id = UUID.randomUUID();
        when(m.repo().findById(id)).thenReturn(Optional.empty());

        m.listener().onBookingCancelled(new BookingDomainEvent.BookingCancelled(
                id, "gone@example.com", "INN-MISSING", Instant.now()));

        verifyNoInteractions(m.email(), m.sms(), m.wa());
    }
}
