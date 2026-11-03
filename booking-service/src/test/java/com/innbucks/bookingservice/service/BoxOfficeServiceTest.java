package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The box office (V35): only the event's own organizer sells, and the checks fail closed. */
class BoxOfficeServiceTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID ORGANIZER = UUID.randomUUID();

    private final BookingService bookings = mock(BookingService.class);
    private final EventServiceClient events = mock(EventServiceClient.class);

    @SuppressWarnings("unchecked")
    private BoxOfficeService service() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        return new BoxOfficeService(bookings, provider, "token");
    }

    private static CreateBookingRequestDTO request() {
        CreateBookingRequestDTO r = new CreateBookingRequestDTO();
        r.setEventId(EVENT);
        return r;
    }

    private void window(String status, UUID tenant) {
        when(events.salesWindow(eq(EVENT), anyString())).thenReturn(ApiResult.ok("ok",
                EventSalesWindowDTO.builder().eventId(EVENT).status(status).tenantUserUuid(tenant).build()));
    }

    @Test
    void theOrganizer_sells_andTheSaleIsConfirmedAsTheirs() {
        window(EventSalesWindowDTO.ON_SALE, ORGANIZER);
        UUID held = UUID.randomUUID();
        when(bookings.createBoxOfficeBooking(any(), any(), any()))
                .thenReturn(BookingResponseDTO.builder().id(held).build());
        BookingResponseDTO sold = BookingResponseDTO.builder().id(held).build();
        when(bookings.confirmBoxOfficeSale(held, ORGANIZER, "organizer@example.com")).thenReturn(sold);

        assertSame(sold, service().sell(request(), "buyer@example.com", "+263771234567", ORGANIZER, ORGANIZER,
                "organizer@example.com"));
        verify(bookings).createBoxOfficeBooking(eq("buyer@example.com"), eq("+263771234567"), any());
    }

    @Test
    void someoneElsesEvent_anUnknownOne_andAnUnansweredQuestion_sellNothing() {
        window(EventSalesWindowDTO.ON_SALE, UUID.randomUUID());
        assertThrows(AccessDeniedException.class, () -> service().sell(request(), null, "+263771234567",
                ORGANIZER, ORGANIZER, "o"));

        window(EventSalesWindowDTO.NOT_FOUND, null);
        ApiProblemException unknown = assertThrows(ApiProblemException.class, () -> service().sell(request(), null,
                "+263771234567", ORGANIZER, ORGANIZER, "o"));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());

        when(events.salesWindow(eq(EVENT), anyString())).thenReturn(ApiResult.ok("down", null));
        ApiProblemException down = assertThrows(ApiProblemException.class, () -> service().sell(request(), null,
                "+263771234567", ORGANIZER, ORGANIZER, "o"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, down.getStatus());
        assertEquals("TRY_AGAIN", down.getErrorCode());

        assertThrows(AccessDeniedException.class, () -> service().sell(request(), null, "+263771234567",
                null, ORGANIZER, "o"));
        verify(bookings, never()).createBoxOfficeBooking(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVoid_asksTheEventsStartLive_andAnUnansweredOneIsA503() {
        UUID booking = UUID.randomUUID();
        BoxOfficeService service = service();
        service.voidSale(booking, "Buyer changed their mind", ORGANIZER, ORGANIZER, "o");
        ArgumentCaptor<Function<UUID, LocalDateTime>> start = ArgumentCaptor.forClass(Function.class);
        verify(bookings).voidCashSale(eq(booking), eq(ORGANIZER), eq(ORGANIZER), eq("o"),
                eq("Buyer changed their mind"), start.capture());

        LocalDateTime at = LocalDateTime.of(2030, 11, 20, 8, 0);
        when(events.getEventInternal(eq(EVENT), anyString())).thenReturn(ApiResult.ok("ok",
                EventLookupDTO.builder().eventId(EVENT).startDateTime(at).build()));
        assertEquals(at, start.getValue().apply(EVENT));

        when(events.getEventInternal(eq(EVENT), anyString())).thenReturn(ApiResult.ok("down", null));
        assertEquals("TRY_AGAIN", assertThrows(ApiProblemException.class,
                () -> start.getValue().apply(EVENT)).getErrorCode());
    }
}
