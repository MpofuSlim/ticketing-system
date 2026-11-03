package com.innbucks.bookingservice.testsupport;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import org.springframework.beans.factory.ObjectProvider;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An event-service for the hand-built {@code BookingService}s that create
 * bookings. {@code createBooking} asks event-service whether the event is on
 * sale before anything else (event-service V15), and fails CLOSED when it
 * cannot ask: a service built with no event client now answers every booking
 * with a 503. This one says every event is on sale.
 */
public final class OnSaleEvents {

    private OnSaleEvents() {
    }

    /** Every event on sale, no organizer named. */
    public static ObjectProvider<EventServiceClient> provider() {
        return providerFor(client(null));
    }

    /** Every event on sale, run by {@code organizer}. */
    public static EventServiceClient client(UUID organizer) {
        EventServiceClient client = mock(EventServiceClient.class);
        when(client.salesWindow(any(), any())).thenAnswer(call -> ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder()
                        .eventId(call.getArgument(0))
                        .status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer)
                        .build()));
        return client;
    }

    @SuppressWarnings("unchecked")
    public static ObjectProvider<EventServiceClient> providerFor(EventServiceClient client) {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
