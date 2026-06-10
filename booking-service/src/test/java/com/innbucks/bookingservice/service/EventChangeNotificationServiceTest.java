package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventChangeNotificationService}. The {@code @Async} /
 * {@code @Transactional} annotations are inert when the method is invoked
 * directly (no Spring proxy), so the fan-out runs synchronously and
 * deterministically here. Pins SMS-primary → WhatsApp-fallback per recipient,
 * CONFIRMED-only targeting, the message copy, and best-effort isolation.
 */
class EventChangeNotificationServiceTest {

    private static Booking confirmed(String phone, String ref) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        b.setPhoneNumber(phone);
        b.setConfirmationNumber(ref);
        return b;
    }

    @Test
    void cancelled_smsPrimary_toEveryConfirmedAttendee() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(confirmed("+263771111111", "INN-A"), confirmed("+263772222222", "INN-B")));

        new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "CANCELLED", "Jazz Night", null, null);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263771111111"), msg.capture(), startsWith("EVENT-CHANGE-"));
        verify(sms).sendSms(eq("+263772222222"), anyString(), startsWith("EVENT-CHANGE-"));
        assertThat(msg.getValue())
                .contains("Jazz Night")
                .containsIgnoringCase("cancelled")
                .contains("INN-A")
                .containsIgnoringCase("refund");
        // SMS succeeded → WhatsApp fallback not used.
        verifyNoInteractions(wa);
    }

    @Test
    void updated_messageCarriesNewTimeAndVenue() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(confirmed("+263771111111", "INN-A")));

        new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "UPDATED", "Jazz Night", "2026-07-01T19:00", "New Stadium");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263771111111"), msg.capture(), anyString());
        assertThat(msg.getValue())
                .contains("Jazz Night")
                .contains("2026-07-01T19:00")
                .contains("New Stadium")
                .contains("INN-A");
    }

    @Test
    void smsFailure_fallsBackToWhatsApp_perRecipient() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(confirmed("+263771111111", "INN-A")));
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "CANCELLED", "Jazz Night", null, null);

        verify(wa).sendCustomNotification(eq("+263771111111"), contains("Jazz Night"));
    }

    @Test
    void oneRecipientFailingBothChannels_doesNotStopTheRest() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        Booking bad = confirmed("+263770000000", "INN-BAD");
        Booking good = confirmed("+263771111111", "INN-OK");
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(bad, good));
        // The first recipient's number blows up on BOTH channels.
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(eq("+263770000000"), anyString(), anyString());
        doThrow(new NotificationDeliveryException("wa down"))
                .when(wa).sendCustomNotification(eq("+263770000000"), anyString());

        assertThatCode(() -> new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "CANCELLED", "Jazz Night", null, null))
                .doesNotThrowAnyException();

        // The good recipient still got their SMS despite the bad one failing first.
        verify(sms).sendSms(eq("+263771111111"), anyString(), anyString());
    }

    @Test
    void attendeeWithNoPhone_isSkipped() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(confirmed(null, "INN-NOPHONE"), confirmed("+263771111111", "INN-OK")));

        new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "CANCELLED", "Jazz Night", null, null);

        verify(sms, times(1)).sendSms(eq("+263771111111"), anyString(), anyString());
        verifyNoMoreInteractions(sms);
    }

    @Test
    void noConfirmedAttendees_sendsNothing() {
        BookingRepository repo = mock(BookingRepository.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        WhatsAppNotificationClient wa = mock(WhatsAppNotificationClient.class);
        UUID eventId = UUID.randomUUID();
        when(repo.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED)).thenReturn(List.of());

        new EventChangeNotificationService(repo, sms, wa)
                .broadcast(eventId, "CANCELLED", "Jazz Night", null, null);

        verifyNoInteractions(sms, wa);
    }
}
