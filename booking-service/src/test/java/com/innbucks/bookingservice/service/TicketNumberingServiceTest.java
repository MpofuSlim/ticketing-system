package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.NumberingDTO;
import com.innbucks.bookingservice.dto.NumberingRequestDTO;
import com.innbucks.bookingservice.entity.NumberSeries;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.numbering.TicketNumberAssigner;
import com.innbucks.bookingservice.repository.NumberSeriesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The organizer's numbering endpoints (V34): ownership, the lock after the first number, defaults. */
class TicketNumberingServiceTest {

    private static final UUID CATEGORY = UUID.fromString("5c2e7b1a-3d4f-4e6a-8b9c-0d1e2f3a4b5c");
    private static final UUID EVENT = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID OWNER = UUID.randomUUID();

    private final NumberSeriesRepository repo = mock(NumberSeriesRepository.class);
    private final TicketNumberAssigner assigner = mock(TicketNumberAssigner.class);
    private final SeatServiceClient seats = mock(SeatServiceClient.class);
    private final EventServiceClient events = mock(EventServiceClient.class);

    @SuppressWarnings("unchecked")
    private TicketNumberingService service() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        when(seats.getCategory(CATEGORY)).thenReturn(ApiResult.ok("ok",
                CategoryLookupDTO.builder().seatCategoryId(CATEGORY).eventId(EVENT).name("10 km").build()));
        when(events.getEventInternal(eq(EVENT), any())).thenReturn(ApiResult.ok("ok",
                EventLookupDTO.builder().eventId(EVENT).tenantUserUuid(OWNER).build()));
        return new TicketNumberingService(repo, assigner, seats, provider, "token", null);
    }

    static NumberSeries series(String label, String prefix, int start, int padding, int assigned) {
        NumberSeries s = mock(NumberSeries.class);
        when(s.getCategoryId()).thenReturn(CATEGORY);
        when(s.getEventId()).thenReturn(EVENT);
        when(s.getLabel()).thenReturn(label);
        when(s.getPrefix()).thenReturn(prefix);
        when(s.getStartValue()).thenReturn(start);
        when(s.getPadding()).thenReturn((short) padding);
        when(s.getAssignedCount()).thenReturn(assigned);
        return s;
    }

    @Test
    void set_writesTheTerms_withDefaultsForWhatWasLeftOut() {
        TicketNumberingService service = service();
        when(repo.upsertWhileUnused(any(), any(), anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(1);
        NumberSeries stored1 = series("Number", "", 1, 0, 0);
        when(repo.findById(CATEGORY)).thenReturn(Optional.of(stored1));

        NumberingDTO dto = service.set(CATEGORY, new NumberingRequestDTO(), OWNER, "o", false);

        verify(repo).upsertWhileUnused(eq(CATEGORY), eq(EVENT), eq("Number"), eq(""), eq(1), eq(0), eq("o"), any());
        assertEquals("1", dto.getExample());
        assertFalse(dto.isLocked());
    }

    @Test
    void set_afterTheFirstNumber_isRefusedWithHowManyWentOut() {
        TicketNumberingService service = service();
        when(repo.upsertWhileUnused(any(), any(), anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(0);
        NumberSeries stored2 = series("Bib", "T", 1, 3, 37);
        when(repo.findById(CATEGORY)).thenReturn(Optional.of(stored2));

        ApiProblemException refused = assertThrows(ApiProblemException.class, () -> service.set(CATEGORY,
                NumberingRequestDTO.builder().prefix("R").build(), OWNER, "o", false));

        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        assertEquals("NUMBERING_IN_USE", refused.getErrorCode());
        assertEquals("Numbers have already been given in this category, so its numbering can't change.",
                refused.getMessage());
        assertEquals(37, refused.getExtra().get("assigned"));
    }

    @Test
    void someoneElsesEvent_isRefusedBeforeAnythingIsWritten_andSuperAdminIsNotAsked() {
        TicketNumberingService service = service();
        assertThrows(AccessDeniedException.class, () -> service.set(CATEGORY, new NumberingRequestDTO(),
                UUID.randomUUID(), "x", false));
        assertThrows(AccessDeniedException.class, () -> service.remove(CATEGORY, null, "x", false));
        assertThrows(AccessDeniedException.class, () -> service.assignSold(CATEGORY, UUID.randomUUID(), "x", false));
        verify(repo, never()).upsertWhileUnused(any(), any(), anyString(), anyString(), anyInt(), anyInt(), any(),
                any());
        verify(repo, never()).deleteWhileUnused(any());
        verifyNoInteractions(assigner);

        when(repo.deleteWhileUnused(CATEGORY)).thenReturn(1);
        service.remove(CATEGORY, null, "admin@example.com", true);
        // Asked for the two calls that carried an organizer; never for the one without, nor for SUPER_ADMIN.
        verify(events, times(2)).getEventInternal(eq(EVENT), any());
    }

    @Test
    void anUnansweredOwnershipCheck_failsClosed() {
        TicketNumberingService service = service();
        when(events.getEventInternal(eq(EVENT), any())).thenThrow(new RuntimeException("down"));
        assertThrows(AccessDeniedException.class, () -> service.list(EVENT, OWNER, false));
        verify(repo, never()).findByEventIdOrderByCreatedAtAscCategoryIdAsc(any());
    }

    @Test
    void remove_isRefusedOnceInUse_andIsA404WhenThereIsNone() {
        TicketNumberingService service = service();
        when(repo.deleteWhileUnused(CATEGORY)).thenReturn(0);
        NumberSeries stored3 = series("Bib", "T", 1, 3, 2);
        when(repo.findById(CATEGORY)).thenReturn(Optional.of(stored3));
        assertEquals("NUMBERING_IN_USE", assertThrows(ApiProblemException.class,
                () -> service.remove(CATEGORY, OWNER, "o", false)).getErrorCode());

        when(repo.findById(CATEGORY)).thenReturn(Optional.empty());
        assertEquals("This ticket category has no numbering.", assertThrows(NotFoundException.class,
                () -> service.remove(CATEGORY, OWNER, "o", false)).getMessage());
    }

    @Test
    void aCategorySeatServiceNoLongerHas_isA404() {
        TicketNumberingService service = service();
        when(seats.getCategory(CATEGORY)).thenReturn(ApiResult.ok("ok", null));
        assertEquals("This ticket category is no longer available.", assertThrows(NotFoundException.class,
                () -> service.set(CATEGORY, new NumberingRequestDTO(), OWNER, "o", false)).getMessage());
    }

    @Test
    void assignSold_reportsHowManyAndTheSeriesAfter_andIsA404WithoutASeries() {
        TicketNumberingService service = service();
        when(assigner.assignSold(CATEGORY)).thenReturn(OptionalInt.of(37));
        NumberSeries stored4 = series("Bib", "T", 1, 3, 37);
        when(repo.findById(CATEGORY)).thenReturn(Optional.of(stored4));

        var result = service.assignSold(CATEGORY, OWNER, "o", false);
        assertEquals(37, result.numberedNow());
        assertEquals("T038", result.numbering().getNextNumber());
        assertTrue(result.numbering().isLocked());

        when(assigner.assignSold(CATEGORY)).thenReturn(OptionalInt.empty());
        assertThrows(NotFoundException.class, () -> service.assignSold(CATEGORY, OWNER, "o", false));
    }

    @Test
    void theLabel_isPlainText_andBlankIsTheDefault() {
        assertEquals("Bib", TicketNumberingService.cleanLabel("  Bib "));
        assertEquals("Race no", TicketNumberingService.cleanLabel("Race‮\nno"));
        assertEquals("b", TicketNumberingService.cleanLabel("<b>"));
        assertEquals("Number", TicketNumberingService.cleanLabel("   "));
        assertEquals("Number", TicketNumberingService.cleanLabel(null));
    }
}
