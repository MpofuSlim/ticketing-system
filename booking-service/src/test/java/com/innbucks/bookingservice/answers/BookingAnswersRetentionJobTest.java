package com.innbucks.bookingservice.answers;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.testsupport.OnSaleEvents;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Answers are deleted 90 days after the event's LIVE end (V32): a postponed
 * event keeps them, an unreachable event-service deletes nothing.
 */
class BookingAnswersRetentionJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 3, 10, 2, 40);

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final EventServiceClient events = mock(EventServiceClient.class);
    private final BookingAnswersRetentionJob job = new BookingAnswersRetentionJob(bookingRepo, itemRepo,
            OnSaleEvents.providerFor(events), mock(PlatformTransactionManager.class), "token", "P90D");

    private void due(UUID... eventIds) {
        when(bookingRepo.findEventIdsWithAnswersDue(eq(NOW), anyInt())).thenReturn(List.of(eventIds));
    }

    private void window(UUID eventId, String status, LocalDateTime end) {
        when(events.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("ok",
                EventSalesWindowDTO.builder().eventId(eventId).status(status).endDateTime(end).build()));
    }

    @Test
    void anEventThatEndedOver90DaysAgo_hasItsAnswersDeleted() {
        UUID event = UUID.randomUUID();
        due(event);
        window(event, EventSalesWindowDTO.ENDED, NOW.minusDays(91));

        assertEquals(1, job.purgeDue(NOW));

        verify(itemRepo).clearAnswersForEvent(event);
        verify(bookingRepo).markAnswersPurged(event, NOW);
        verify(bookingRepo, never()).rescheduleAnswersPurge(any(), any());
    }

    @Test
    void aPostponedEvent_keepsItsAnswers_andIsRescheduled() {
        UUID event = UUID.randomUUID();
        due(event);
        LocalDateTime newEnd = NOW.minusDays(10);
        window(event, EventSalesWindowDTO.ENDED, newEnd);

        assertEquals(0, job.purgeDue(NOW));

        verify(bookingRepo).rescheduleAnswersPurge(event, newEnd.plusDays(90));
        verify(itemRepo, never()).clearAnswersForEvent(any());
    }

    @Test
    void aDeletedEvent_isPurgedByTheStoredDate() {
        UUID event = UUID.randomUUID();
        due(event);
        window(event, EventSalesWindowDTO.NOT_FOUND, null);

        assertEquals(1, job.purgeDue(NOW));
        verify(itemRepo).clearAnswersForEvent(event);
    }

    @Test
    void whenEventServiceCannotBeAsked_nothingIsDeleted_andOtherEventsStillAre() {
        UUID unreachable = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        due(unreachable, failing, ok);
        when(events.salesWindow(eq(unreachable), any())).thenReturn(
                ApiResult.<EventSalesWindowDTO>builder().code("503").message("event unavailable").build());
        when(events.salesWindow(eq(failing), any())).thenThrow(new RuntimeException("timeout"));
        window(ok, EventSalesWindowDTO.ENDED, NOW.minusDays(100));

        assertEquals(1, job.purgeDue(NOW));

        verify(itemRepo, never()).clearAnswersForEvent(unreachable);
        verify(itemRepo, never()).clearAnswersForEvent(failing);
        verify(itemRepo).clearAnswersForEvent(ok);
    }

    @Test
    void nothingDue_asksNobody() {
        due();
        assertEquals(0, job.purgeDue(NOW));
        verifyNoInteractions(events);
    }
}
