package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.cache.EventLookupCache;
import com.innbucks.bookingservice.cache.ReadCacheConfig;
import com.innbucks.bookingservice.cache.ReadCacheProperties;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Which {@link BookingService} event lookups go through {@link EventLookupCache}
 * (the public ticket list) and which must not (the event-ownership check that
 * gates an organizer's guest list).
 */
class BookingServiceEventLookupCachingTest {

    private static final String PHONE = "+263771234567";
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final EventServiceClient eventClient = mock(EventServiceClient.class);
    private BookingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(eventClient);
        service = new BookingService(bookingRepo, itemRepo,
                mock(CategoryInventoryRepository.class), mock(SeatServiceClient.class),
                mock(ApplicationEventPublisher.class), new QrCodeGenerator(),
                provider, mock(PlatformTransactionManager.class));
        service.setEventLookupCache(new EventLookupCache(
                new ReadCacheConfig().readCacheManager(new ReadCacheProperties())));

        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).plusDays(5);
        when(eventClient.getEventInternal(eq(EVENT), any())).thenReturn(ApiResult.ok("ok",
                EventLookupDTO.builder().eventId(EVENT).tenantUserUuid(OWNER).title("Jazz Festival")
                        .venue("HICC").startDateTime(start).endDateTime(start.plusHours(6)).build()));

        Booking booking = Booking.builder()
                .id(UUID.randomUUID()).eventId(EVENT).phoneNumber(PHONE)
                .confirmationNumber("INN-20260502-AB12CD").status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00")).createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        booking.setItems(List.of(BookingItem.builder().seatId(UUID.randomUUID()).categoryName("VIP")
                .priceAtBooking(new BigDecimal("100.00")).ticketNumber("T-1").build()));
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(List.of(booking));
        when(itemRepo.findByEventIdWithBooking(EVENT)).thenReturn(List.of());
    }

    @Test
    void theTicketList_asksEventServiceOncePerEvent_acrossRequests() {
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, null).get(0).getEventTitle())
                .isEqualTo("Jazz Festival");
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, null).get(0).getVenue()).isEqualTo("HICC");

        verify(eventClient, times(1)).getEventInternal(eq(EVENT), any());
    }

    @Test
    void theOwnershipCheck_alwaysAsksEventService_evenWithTheLookupCached() {
        service.getPublicTicketsByPhoneNumber(PHONE, null); // lookup now cached

        service.getBookingsByEvent(EVENT, OWNER, false);
        service.getBookingsByEvent(EVENT, OWNER, false);
        verify(eventClient, times(3)).getEventInternal(eq(EVENT), any());

        // ...and it still fails CLOSED when event-service cannot answer.
        when(eventClient.getEventInternal(eq(EVENT), any())).thenThrow(new RuntimeException("down"));
        assertThatThrownBy(() -> service.getBookingsByEvent(EVENT, OWNER, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aFailedLookup_isRetriedOnTheNextRequest() {
        when(eventClient.getEventInternal(eq(EVENT), any()))
                .thenThrow(new RuntimeException("down"))
                .thenReturn(ApiResult.ok("ok", EventLookupDTO.builder()
                        .eventId(EVENT).tenantUserUuid(OWNER).title("Jazz Festival").build()));

        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, null).get(0).getEventTitle()).isNull();
        assertThat(service.getPublicTicketsByPhoneNumber(PHONE, null).get(0).getEventTitle())
                .isEqualTo("Jazz Festival");
    }
}
