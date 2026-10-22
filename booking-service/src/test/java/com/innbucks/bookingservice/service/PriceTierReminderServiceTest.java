package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventReminderDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.EventReminderSubscription;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.EventReminderSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Opt-in price-tier reminders: who follows what, and which tier triggers one message. */
class PriceTierReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final String PHONE = "+263771234567";

    private final UUID eventId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();
    private final UUID earlyId = UUID.randomUUID();
    private final UUID phaseId = UUID.randomUUID();

    private EventReminderSubscriptionRepository subs;
    private BookingItemRepository items;
    private SeatServiceClient seat;
    private EventServiceClient events;
    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private PriceTierReminderService service;
    /** (subscription, tier) pairs already claimed, like price_tier_reminders_sent. */
    private final Set<List<UUID>> claimed = new HashSet<>();

    @BeforeEach
    void setUp() {
        subs = mock(EventReminderSubscriptionRepository.class);
        items = mock(BookingItemRepository.class);
        seat = mock(SeatServiceClient.class);
        events = mock(EventServiceClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        when(subs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subs.claimReminder(any(), any(), any())).thenAnswer(inv ->
                claimed.add(List.of(inv.<UUID>getArgument(0), inv.<UUID>getArgument(1))) ? 1 : 0);
        when(events.getEvent(eventId)).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).title("Harare Jazz Festival").build()));
        service = new PriceTierReminderService(subs, items, seat, events, whatsApp, sms, new MarketTimeZone("ZW"),
                null, "internal", 24);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** VIP 150.00: Early bird 100.00 for 10 tickets until {@code earlyEnds}, then Phase 1 120.00. */
    private void seatReports(String earlyEnds) {
        when(seat.getCategoriesWithTiersEnding(anyString(), anyString(), eq("internal"))).thenReturn(ApiResult.ok(List.of(
                CategoryLookupDTO.builder().seatCategoryId(categoryId).eventId(eventId).name("VIP")
                        .price(new BigDecimal("150.00")).priceTiers(List.of(
                                CategoryLookupDTO.PriceTier.builder().id(earlyId).name("Early bird")
                                        .price(new BigDecimal("100.00")).endsAt(earlyEnds).quantity(10).build(),
                                CategoryLookupDTO.PriceTier.builder().id(phaseId).name("Phase 1")
                                        .price(new BigDecimal("120.00")).endsAt("2026-09-08T20:00:00Z").build()))
                        .build())));
    }

    private void sold(long earlyBird) {
        when(items.countActiveByCategoryAndPriceTiers(categoryId, List.of(earlyId), Booking.BookingStatus.CANCELLED))
                .thenReturn(List.of(new BookingItemRepository.TierActiveItemCount() {
                    public UUID getPriceTierId() { return earlyId; }
                    public Long getCount() { return earlyBird; }
                }));
    }

    private EventReminderSubscription follower() {
        EventReminderSubscription sub = EventReminderSubscription.builder().id(UUID.randomUUID())
                .eventId(eventId).phoneNumber(PHONE).build();
        when(subs.findByEventIdInAndUnsubscribedAtIsNull(Set.of(eventId))).thenReturn(List.of(sub));
        return sub;
    }

    @Test
    void theTierOnSale_endingWithinADay_isAnnouncedOnce_atTheMarketsClock() {
        seatReports("2026-09-08T21:59:59Z");
        sold(4);
        follower();

        service.remind();
        service.remind();

        verify(whatsApp, times(1)).sendCustomNotification(PHONE, "Harare Jazz Festival: Early bird tickets for VIP "
                + "(100.00) end Tue 8 Sep at 23:59. Book on InnBucks before the price goes up.");
        verifyNoInteractions(sms);
    }

    @Test
    void aTierAlreadySoldOut_isNotOnSale_soNothingIsSent() {
        seatReports("2026-09-08T21:59:59Z");
        sold(10);
        follower();

        // Phase 1 is on sale now and ends at 20:00Z, inside the window: that one is announced instead.
        service.remind();

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("Phase 1 tickets for VIP (120.00)"));
        verify(whatsApp, never()).sendCustomNotification(any(), contains("Early bird"));
    }

    @Test
    void aLaterTierEnding_whileAnEarlierOneIsOnSale_isNotAnnounced() {
        // Early bird ends next week and is on sale; Phase 1 ending today is not on sale yet.
        seatReports("2026-09-15T21:59:59Z");
        sold(0);
        follower();

        service.remind();

        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void whatsAppFailing_fallsBackToSms() {
        seatReports("2026-09-08T21:59:59Z");
        sold(0);
        follower();
        doThrow(new RuntimeException("gateway down")).when(whatsApp).sendCustomNotification(any(), any());

        service.remind();

        verify(sms).sendSms(eq(PHONE), contains("Early bird tickets for VIP"), startsWith("TIER-RMD-"));
    }

    @Test
    void seatServiceUnreachable_skipsTheRun() {
        when(seat.getCategoriesWithTiersEnding(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("down"));

        assertDoesNotThrow(() -> service.remind());
        verifyNoInteractions(whatsApp, sms, subs);
    }

    @Test
    void subscribe_isIdempotent_andFollowsAgainAfterStopping() {
        EventReminderSubscription stopped = EventReminderSubscription.builder().id(UUID.randomUUID())
                .eventId(eventId).phoneNumber(PHONE).createdAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .unsubscribedAt(LocalDateTime.of(2026, 9, 2, 0, 0)).build();
        when(subs.findByEventIdAndPhoneNumber(eventId, PHONE)).thenReturn(Optional.of(stopped));

        EventReminderDTO again = service.subscribe(eventId, PHONE);

        assertNull(stopped.getUnsubscribedAt());
        assertEquals(LocalDateTime.of(2026, 9, 8, 8, 0), again.getSubscribedAt());
        service.subscribe(eventId, PHONE);
        verify(subs, times(1)).saveAndFlush(any());
    }

    @Test
    void subscribe_needsAPhoneOnTheSession_andIsCapped() {
        assertEquals("NO_PHONE_CLAIM",
                assertThrows(ApiProblemException.class, () -> service.subscribe(eventId, " ")).getErrorCode());
        when(subs.findByEventIdAndPhoneNumber(eventId, PHONE)).thenReturn(Optional.empty());
        when(subs.countByPhoneNumberAndUnsubscribedAtIsNull(PHONE)).thenReturn(50L);

        assertEquals("REMINDER_LIMIT_REACHED",
                assertThrows(ApiProblemException.class, () -> service.subscribe(eventId, PHONE)).getErrorCode());
        verify(subs, never()).saveAndFlush(any());
    }
}
