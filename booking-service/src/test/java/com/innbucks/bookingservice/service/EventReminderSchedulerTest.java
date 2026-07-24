package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Window/marker logic of the two pre-event reminder stages: T-2-days
 * (SMS + email, reminder2dSentAt) and day-of (WhatsApp + SMS + email,
 * reminderSentAt). Exactly-once per stage per booking, silent consumption for
 * already-started events AND for 2d markers of bookings already inside the
 * day-of window (one reminder for late bookers, not two), defer when
 * event-service can't say when the event starts.
 */
class EventReminderSchedulerTest {

    private BookingRepository bookings;
    private EventServiceClient events;
    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private EmailNotificationClient email;
    private EventReminderScheduler scheduler;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        events = mock(EventServiceClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        email = mock(EmailNotificationClient.class);
        scheduler = new EventReminderScheduler(bookings, events, whatsApp, sms, email, 24, 48);
        // Default: neither stage has anything to scan; tests override per stage.
        lenient().when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of());
        lenient().when(bookings.findEventIdsWithUn2dRemindedConfirmed()).thenReturn(List.of());
    }

    private Booking confirmed(String phone, String emailAddr) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setEventId(eventId);
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        b.setPhoneNumber(phone);
        b.setUserEmail(emailAddr);
        b.setConfirmationNumber("CONF-1");
        return b;
    }

    private void eventStartsInHours(long hours) {
        EventLookupDTO dto = EventLookupDTO.builder()
                .eventId(eventId)
                .title("Test Fest")
                .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusHours(hours))
                .build();
        when(events.getEvent(eventId))
                .thenReturn(ApiResult.<EventLookupDTO>builder().code("200").data(dto).build());
    }

    private void dayOfScanReturns(Booking... items) {
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminderSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(items));
    }

    private void twoDayScanReturns(Booking... items) {
        when(bookings.findEventIdsWithUn2dRemindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminder2dSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(items));
    }

    // ---- day-of stage -------------------------------------------------------

    @Test
    void dayOf_insideWindow_sendsAllThreeChannels_andStampsMarker() {
        Booking b = confirmed("+263771234567", "guest@example.com");
        dayOfScanReturns(b);
        eventStartsInHours(5);

        scheduler.remind();

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("Test Fest"));
        verify(sms).sendSms(eq("+263771234567"), contains("Test Fest"), startsWith("RMD-DAY-CONF-1"));
        verify(email).sendEmail(eq("guest@example.com"), contains("today"),
                contains("Test Fest"), startsWith("RMD-DAY-CONF-1"));
        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void dayOf_outsideWindow_doesNothingAndKeepsMarkerNull() {
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        eventStartsInHours(30);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(bookings, never()).saveAll(any());
    }

    @Test
    void dayOf_eventAlreadyStarted_consumesMarkerWithoutSending() {
        Booking b = confirmed("+263771234567", "guest@example.com");
        dayOfScanReturns(b);
        eventStartsInHours(-2);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void dayOf_channelFailure_stillStampsMarker_noRetrySpam() {
        Booking b = confirmed("+263771234567", "guest@example.com");
        dayOfScanReturns(b);
        eventStartsInHours(3);
        doThrow(new RuntimeException("gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());
        doThrow(new RuntimeException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        scheduler.remind();

        // Independent channels: email still goes out despite the other two failing.
        verify(email).sendEmail(eq("guest@example.com"), anyString(), anyString(), anyString());
        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void dayOf_bookingWithoutContacts_isMarkedWithoutSend() {
        Booking b = confirmed(null, null);
        dayOfScanReturns(b);
        eventStartsInHours(3);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        assertThat(b.getReminderSentAt()).isNotNull();
    }

    @Test
    void eventLookupUnavailable_defersBothStagesWithoutConsumingMarkers() {
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findEventIdsWithUn2dRemindedConfirmed()).thenReturn(List.of(eventId));
        // Circuit-open fallback shape: ApiResult with null data.
        when(events.getEvent(eventId))
                .thenReturn(ApiResult.<EventLookupDTO>builder().code("503").data(null).build());

        scheduler.remind();

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(bookings, never()).saveAll(any());
    }

    // ---- T-2-days stage -----------------------------------------------------

    @Test
    void twoDay_insideWindow_sendsSmsAndEmail_notWhatsApp_andStampsMarker() {
        Booking b = confirmed("+263771234567", "guest@example.com");
        twoDayScanReturns(b);
        eventStartsInHours(40); // inside 48h, outside 24h

        scheduler.remind();

        verify(sms).sendSms(eq("+263771234567"), contains("in 2 days"), startsWith("RMD-2D-CONF-1"));
        verify(email).sendEmail(eq("guest@example.com"), contains("in 2 days"),
                contains("Test Fest"), startsWith("RMD-2D-CONF-1"));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(b.getReminder2dSentAt()).isNotNull();
        assertThat(b.getReminderSentAt()).as("day-of stage untouched").isNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void twoDay_outsideWindow_doesNothing() {
        when(bookings.findEventIdsWithUn2dRemindedConfirmed()).thenReturn(List.of(eventId));
        eventStartsInHours(72);

        scheduler.remind();

        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(bookings, never()).saveAll(any());
    }

    @Test
    void twoDay_alreadyInsideDayOfWindow_consumesSilently_soLateBookerGetsOneReminder() {
        // Booked the night before: the 2d stage must NOT fire a second
        // reminder — the day-of stage (same tick) carries the single send.
        Booking b = confirmed("+263771234567", "guest@example.com");
        twoDayScanReturns(b);
        dayOfScanReturns(b);
        eventStartsInHours(5);

        scheduler.remind();

        // Exactly ONE SMS and ONE email — from the day-of stage, not two.
        verify(sms).sendSms(eq("+263771234567"), anyString(), startsWith("RMD-DAY-CONF-1"));
        verify(sms, never()).sendSms(anyString(), anyString(), startsWith("RMD-2D-"));
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), startsWith("RMD-2D-"));
        assertThat(b.getReminder2dSentAt()).isNotNull();
        assertThat(b.getReminderSentAt()).isNotNull();
    }
}
