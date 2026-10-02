package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The post-commit availability decrement: one consume call carrying the
 * event's own id, ticket count and the internal token; nothing for an event
 * that has no tickets or no event id; and never an exception out of the
 * listener — the confirm it follows has already committed.
 */
class EventAvailabilityConsumeListenerTest {

    private final EventServiceClient events = mock(EventServiceClient.class);
    private final EventAvailabilityConsumeListener listener =
            new EventAvailabilityConsumeListener(events, "internal-token");

    private static BookingDomainEvent.BookingConfirmed confirmed(UUID eventId, int tickets) {
        return new BookingDomainEvent.BookingConfirmed(UUID.randomUUID(), "u@example.com",
                "INN-1", Instant.now(), eventId, tickets);
    }

    @Test
    void consumesTheBookingsTicketCount_onItsEvent() {
        UUID eventId = UUID.randomUUID();

        listener.onBookingConfirmed(confirmed(eventId, 3));

        verify(events).consumeAvailability(eventId, 3, "internal-token");
    }

    @Test
    void noTickets_orNoEventId_callsNothing() {
        listener.onBookingConfirmed(confirmed(UUID.randomUUID(), 0));
        listener.onBookingConfirmed(confirmed(null, 2));
        // The pre-availability 4-arg shape carries neither.
        listener.onBookingConfirmed(new BookingDomainEvent.BookingConfirmed(
                UUID.randomUUID(), "u@example.com", "INN-2", Instant.now()));

        verifyNoInteractions(events);
    }

    @Test
    void eventServiceFailure_isSwallowed() {
        when(events.consumeAvailability(any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("event-service down"));

        assertThatCode(() -> listener.onBookingConfirmed(confirmed(UUID.randomUUID(), 1)))
                .doesNotThrowAnyException();
    }
}
