package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Window/marker logic of the pre-event reminder: exactly-once per booking,
 * WhatsApp-only, silent consumption for already-started events, defer when
 * event-service can't say when the event starts.
 */
class EventReminderSchedulerTest {

    private BookingRepository bookings;
    private EventServiceClient events;
    private WhatsAppNotificationClient whatsApp;
    private EventReminderScheduler scheduler;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        events = mock(EventServiceClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        scheduler = new EventReminderScheduler(bookings, events, whatsApp, 24);
    }

    private Booking confirmed(String phone) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setEventId(eventId);
        b.setStatus(Booking.BookingStatus.CONFIRMED);
        b.setPhoneNumber(phone);
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

    @Test
    void insideWindow_sendsWhatsAppAndStampsMarker() {
        Booking b = confirmed("+263771234567");
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminderSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(b));
        eventStartsInHours(5);

        scheduler.remind();

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("Test Fest"));
        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void outsideWindow_doesNothingAndKeepsMarkerNull() {
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        eventStartsInHours(48);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(bookings, never()).saveAll(any());
    }

    @Test
    void eventAlreadyStarted_consumesMarkerWithoutSending() {
        Booking b = confirmed("+263771234567");
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminderSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(b));
        eventStartsInHours(-2);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void eventLookupUnavailable_defersWithoutConsumingMarker() {
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        // Circuit-open fallback shape: ApiResult with null data.
        when(events.getEvent(eventId))
                .thenReturn(ApiResult.<EventLookupDTO>builder().code("503").data(null).build());

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        verify(bookings, never()).saveAll(any());
    }

    @Test
    void whatsAppFailure_stillStampsMarker_noRetrySpam() {
        Booking b = confirmed("+263771234567");
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminderSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(b));
        eventStartsInHours(3);
        doThrow(new RuntimeException("gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        scheduler.remind();

        assertThat(b.getReminderSentAt()).isNotNull();
        verify(bookings).saveAll(List.of(b));
    }

    @Test
    void bookingWithoutPhone_isMarkedWithoutSend() {
        Booking b = confirmed(null);
        when(bookings.findEventIdsWithUnremindedConfirmed()).thenReturn(List.of(eventId));
        when(bookings.findByEventIdAndStatusAndReminderSentAtIsNull(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(List.of(b));
        eventStartsInHours(3);

        scheduler.remind();

        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString());
        assertThat(b.getReminderSentAt()).isNotNull();
    }
}
