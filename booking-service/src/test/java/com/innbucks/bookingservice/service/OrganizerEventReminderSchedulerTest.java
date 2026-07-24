package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.OrganizerEventReminder;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.OrganizerEventReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
 * Window/marker logic of the day-before ORGANIZER reminder: exactly-once per
 * event via the V21 marker, headline numbers in the email, INTERNAL event
 * lookup (the public one strips tenantUserUuid), silent marker for started
 * events, defer (no marker) when a lookup fails, and marker-without-send when
 * the organizer has no business email.
 */
class OrganizerEventReminderSchedulerTest {

    private BookingRepository bookings;
    private OrganizerEventReminderRepository markers;
    private EventServiceClient events;
    private UserServiceClient users;
    private EmailNotificationClient email;
    private OrganizerEventReminderScheduler scheduler;

    private final UUID eventId = UUID.randomUUID();
    private final UUID organizerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        markers = mock(OrganizerEventReminderRepository.class);
        events = mock(EventServiceClient.class);
        users = mock(UserServiceClient.class);
        email = mock(EmailNotificationClient.class);
        scheduler = new OrganizerEventReminderScheduler(
                bookings, markers, events, users, email, "internal-token", 24);
        when(bookings.findEventIdsForOrganizerReminder()).thenReturn(List.of(eventId));
        lenient().when(bookings.countByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED))
                .thenReturn(3L);
        lenient().when(bookings.countConfirmedTickets(eventId)).thenReturn(5L);
        lenient().when(users.lookupTenants(any(), eq("internal-token")))
                .thenReturn(ApiResult.<List<TenantContactDTO>>builder()
                        .code("200")
                        .data(List.of(new TenantContactDTO(
                                organizerUuid, "Chisipite", "Harare", "organizer@school.zw")))
                        .build());
    }

    private void eventStartsInHours(long hours) {
        EventLookupDTO dto = EventLookupDTO.builder()
                .eventId(eventId)
                .tenantUserUuid(organizerUuid)
                .title("Pink Fun Run")
                .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusHours(hours))
                .build();
        when(events.getEventInternal(eventId, "internal-token"))
                .thenReturn(ApiResult.<EventLookupDTO>builder().code("200").data(dto).build());
    }

    @Test
    void insideWindow_emailsHeadlineNumbers_andSavesMarker() {
        eventStartsInHours(10);

        scheduler.remind();

        verify(email).sendEmail(eq("organizer@school.zw"),
                contains("Pink Fun Run"), contains("Tickets sold: 5"), startsWith("ORG-RMD-"));
        verify(markers).save(any(OrganizerEventReminder.class));
    }

    @Test
    void outsideWindow_doesNothing_noMarker() {
        eventStartsInHours(40);

        scheduler.remind();

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(markers, never()).save(any());
    }

    @Test
    void eventAlreadyStarted_savesMarkerWithoutSending() {
        eventStartsInHours(-3);

        scheduler.remind();

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(markers).save(any(OrganizerEventReminder.class));
    }

    @Test
    void eventLookupUnavailable_defersWithoutMarker() {
        when(events.getEventInternal(eventId, "internal-token"))
                .thenReturn(ApiResult.<EventLookupDTO>builder().code("503").data(null).build());

        scheduler.remind();

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(markers, never()).save(any());
    }

    @Test
    void noBusinessEmail_savesMarkerWithoutSending() {
        eventStartsInHours(10);
        when(users.lookupTenants(any(), eq("internal-token")))
                .thenReturn(ApiResult.<List<TenantContactDTO>>builder()
                        .code("200").data(List.of()).build());

        scheduler.remind();

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(markers).save(any(OrganizerEventReminder.class));
    }

    @Test
    void emailFailure_stillSavesMarker_noRetrySpam() {
        eventStartsInHours(10);
        doThrow(new RuntimeException("api down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        scheduler.remind();

        verify(markers).save(any(OrganizerEventReminder.class));
    }
}
