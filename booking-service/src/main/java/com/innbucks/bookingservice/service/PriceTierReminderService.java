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
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Opt-in "the price goes up soon" reminders (V28). A customer subscribes to
 * an event from their own session; about a day before the price tier ON SALE
 * in one of the event's categories ends, they get ONE message naming it,
 * WhatsApp first and SMS if WhatsApp fails.
 *
 * <p><b>Only the tier on sale now.</b> seat-service lists the categories with
 * a tier ending in the next {@code app.booking.tier-reminder-window-hours};
 * this service works out which tier is on sale with the same rule a booking is
 * priced by ({@link PriceTierAllocator}, over its own counts), and reminds only
 * when that tier is the one ending. "Phase 1 ends tomorrow" while Early bird is
 * still on sale would only confuse, and a tier already sold out is not on sale.
 *
 * <p><b>Claim, commit, then send</b>, as {@link EventReminderScheduler} does:
 * each (subscription, tier) is claimed by an INSERT that commits before the
 * send, so each subscriber hears about each tier at most once, on any number
 * of replicas; a crash loses a message rather than repeating one. No send
 * runs with a transaction open.
 */
@Slf4j
@Service
public class PriceTierReminderService {

    /** How many events one phone may follow at once: each costs a message. */
    static final int MAX_SUBSCRIPTIONS = 50;
    static final String NO_PHONE = "NO_PHONE_CLAIM";
    static final String LIMIT = "REMINDER_LIMIT_REACHED";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm");

    private final EventReminderSubscriptionRepository subscriptions;
    private final BookingItemRepository bookingItems;
    private final SeatServiceClient seatService;
    private final EventServiceClient eventService;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final MarketTimeZone market;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final String internalToken;
    private final Duration window;
    private Clock clock = Clock.systemUTC();

    public PriceTierReminderService(EventReminderSubscriptionRepository subscriptions,
                                    BookingItemRepository bookingItems,
                                    SeatServiceClient seatService,
                                    EventServiceClient eventService,
                                    WhatsAppNotificationClient whatsApp,
                                    SmsNotificationClient sms,
                                    MarketTimeZone market,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${innbucks.internal-api-token:}") String internalToken,
                                    @Value("${app.booking.tier-reminder-window-hours:24}") long windowHours) {
        this.subscriptions = subscriptions;
        this.bookingItems = bookingItems;
        this.seatService = seatService;
        this.eventService = eventService;
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.market = market;
        this.readTx = transactionManager == null ? null : TransactionPhases.readOnly(transactionManager);
        this.writeTx = transactionManager == null ? null : TransactionPhases.readWrite(transactionManager);
        this.internalToken = internalToken;
        this.window = Duration.ofHours(windowHours);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ---- the customer's subscriptions ----------------------------------------

    /** Follow an event, by the session's phone. Idempotent; following again after unfollowing switches it back on. */
    public EventReminderDTO subscribe(UUID eventId, String phone) {
        String number = requirePhone(phone);
        try {
            return TransactionPhases.inTransaction(writeTx, () -> {
                EventReminderSubscription sub = subscriptions.findByEventIdAndPhoneNumber(eventId, number)
                        .orElse(null);
                if (sub != null && sub.isActive()) {
                    return toDto(sub);
                }
                if (subscriptions.countByPhoneNumberAndUnsubscribedAtIsNull(number) >= MAX_SUBSCRIPTIONS) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, LIMIT,
                            "You can get reminders for at most " + MAX_SUBSCRIPTIONS + " events at a time.");
                }
                if (sub == null) {
                    sub = EventReminderSubscription.builder().eventId(eventId).phoneNumber(number).build();
                } else {
                    sub.setUnsubscribedAt(null);
                    sub.setCreatedAt(nowUtc());
                }
                return toDto(subscriptions.saveAndFlush(sub));
            });
        } catch (DataIntegrityViolationException raced) {
            // A double tap: the other request created it. Read what it made.
            return TransactionPhases.inTransaction(readTx, () -> toDto(
                    subscriptions.findByEventIdAndPhoneNumber(eventId, number).orElseThrow(() -> raced)));
        }
    }

    /** Stop following an event. Idempotent: not following it is not an error. */
    public void unsubscribe(UUID eventId, String phone) {
        String number = requirePhone(phone);
        TransactionPhases.runInTransaction(writeTx, () -> subscriptions.findByEventIdAndPhoneNumber(eventId, number)
                .filter(EventReminderSubscription::isActive)
                .ifPresent(sub -> {
                    sub.setUnsubscribedAt(nowUtc());
                    subscriptions.save(sub);
                }));
    }

    /** The events the session's phone follows, newest first. */
    public List<EventReminderDTO> list(String phone) {
        String number = requirePhone(phone);
        return TransactionPhases.inTransaction(readTx, () ->
                subscriptions.findByPhoneNumberAndUnsubscribedAtIsNullOrderByCreatedAtDesc(number).stream()
                        .map(PriceTierReminderService::toDto)
                        .toList());
    }

    private static String requirePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, NO_PHONE,
                    "Reminders go to the phone on your account, and this session has none. Sign in with your phone.");
        }
        return phone.trim();
    }

    private static EventReminderDTO toDto(EventReminderSubscription sub) {
        return EventReminderDTO.builder().eventId(sub.getEventId()).subscribedAt(sub.getCreatedAt()).build();
    }

    // ---- the hourly job --------------------------------------------------------

    /** One tier ending soon in one category, as the message names it. */
    record Ending(UUID tierId, String tierName, String categoryName, BigDecimal price, LocalDateTime endsAtUtc) {
    }

    @Scheduled(cron = "${app.booking.tier-reminder-cron:0 25 * * * *}", zone = "UTC")
    @SchedulerLock(name = "PriceTierReminderService.remind", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    public void remind() {
        LocalDateTime now = nowUtc();
        Map<UUID, List<Ending>> endingByEvent = endingTiersOnSale(now);
        if (endingByEvent.isEmpty()) {
            return;
        }
        List<EventReminderSubscription> subs = TransactionPhases.inTransaction(readTx,
                () -> subscriptions.findByEventIdInAndUnsubscribedAtIsNull(endingByEvent.keySet()));
        int sent = 0;
        Map<UUID, String> titles = new HashMap<>();
        for (EventReminderSubscription sub : subs) {
            List<Ending> fresh = claim(sub, endingByEvent.getOrDefault(sub.getEventId(), List.of()), now);
            if (fresh.isEmpty()) {
                continue;
            }
            String title = titles.computeIfAbsent(sub.getEventId(), this::eventTitle);
            if (send(sub, reminderText(title, fresh))) {
                sent++;
            }
        }
        log.info("Price tier reminders sent={} subscriptions={} events={}", sent, subs.size(), endingByEvent.size());
    }

    /**
     * The tiers ending inside the window that are on sale now, by event. A
     * category whose tier on sale is not the one ending (or whose ending tier
     * is sold out, or not yet open) contributes nothing.
     */
    Map<UUID, List<Ending>> endingTiersOnSale(LocalDateTime now) {
        LocalDateTime until = now.plus(window);
        List<CategoryLookupDTO> categories;
        try {
            ApiResult<List<CategoryLookupDTO>> res = seatService.getCategoriesWithTiersEnding(
                    now.atOffset(ZoneOffset.UTC).toString(), until.atOffset(ZoneOffset.UTC).toString(), internalToken);
            categories = res == null || res.getData() == null ? List.of() : res.getData();
        } catch (RuntimeException e) {
            log.warn("Price tier reminders skipped this run: seat-service could not be asked cause={}", e.toString());
            return Map.of();
        }
        Map<UUID, List<Ending>> byEvent = new LinkedHashMap<>();
        for (CategoryLookupDTO category : categories) {
            Ending ending = TransactionPhases.inTransaction(readTx, () -> endingOnSale(category, now, until));
            if (ending != null && category.getEventId() != null) {
                byEvent.computeIfAbsent(category.getEventId(), k -> new ArrayList<>()).add(ending);
            }
        }
        return byEvent;
    }

    /** The category's tier on sale now, when it ends before {@code until}; else null. */
    private Ending endingOnSale(CategoryLookupDTO category, LocalDateTime now, LocalDateTime until) {
        PriceTierAllocator.Counts toCount = PriceTierAllocator.toCount(category, now);
        Map<UUID, Long> counted = new HashMap<>();
        if (!toCount.quantityTiers().isEmpty()) {
            bookingItems.countActiveByCategoryAndPriceTiers(category.getSeatCategoryId(), toCount.quantityTiers(),
                            Booking.BookingStatus.CANCELLED)
                    .forEach(row -> counted.merge(row.getPriceTierId(), row.getCount() == null ? 0L : row.getCount(),
                            Long::sum));
        }
        if (!toCount.keepRefundedTiers().isEmpty()) {
            bookingItems.countRefundedByCategoryAndPriceTiers(category.getSeatCategoryId(), toCount.keepRefundedTiers())
                    .forEach(row -> counted.merge(row.getPriceTierId(), row.getCount() == null ? 0L : row.getCount(),
                            Long::sum));
        }
        PriceTierAllocator.TicketPrice onSale = PriceTierAllocator.allocate(category, 1, counted, Map.of(), now).get(0);
        if (onSale.tierId() == null || category.getPriceTiers() == null) {
            return null;
        }
        for (CategoryLookupDTO.PriceTier tier : category.getPriceTiers()) {
            if (onSale.tierId().equals(tier.getId()) && tier.getEndsAt() != null) {
                LocalDateTime endsAt = PriceTierAllocator.parseUtc(tier.getEndsAt());
                if (endsAt != null && endsAt.isBefore(until)) {
                    return new Ending(tier.getId(), tier.getName(), category.getName(), tier.getPrice(), endsAt);
                }
            }
        }
        return null;
    }

    /** Claim each ending tier for this subscriber, committed before any send; returns the ones this run claimed. */
    private List<Ending> claim(EventReminderSubscription sub, List<Ending> endings, LocalDateTime now) {
        return TransactionPhases.inTransaction(writeTx, () -> endings.stream()
                .filter(e -> subscriptions.claimReminder(sub.getId(), e.tierId(), now) == 1)
                .toList());
    }

    /** WhatsApp, then SMS if WhatsApp fails. The phone came from the customer's own session. */
    private boolean send(EventReminderSubscription sub, String text) {
        try {
            whatsApp.sendCustomNotification(sub.getPhoneNumber(), text);
            return true;
        } catch (RuntimeException whatsAppFailed) {
            try {
                sms.sendSms(sub.getPhoneNumber(), text,
                        "TIER-RMD-" + UUID.randomUUID().toString().substring(0, 8));
                return true;
            } catch (RuntimeException smsFailed) {
                log.warn("Price tier reminder not delivered subscriptionId={} whatsapp={} sms={}",
                        sub.getId(), whatsAppFailed.toString(), smsFailed.toString());
                return false;
            }
        }
    }

    private String eventTitle(UUID eventId) {
        try {
            ApiResult<EventLookupDTO> res = eventService.getEvent(eventId);
            String title = res == null || res.getData() == null ? null : res.getData().getTitle();
            return title == null || title.isBlank() ? "An event you follow" : title.trim();
        } catch (RuntimeException e) {
            return "An event you follow";
        }
    }

    /**
     * One line, plain ASCII, the time at the market's wall clock, e.g.
     * "Harare Jazz Festival: Early bird tickets for VIP (100.00) end Mon 8 Sep
     * at 23:59. Book on InnBucks before the price goes up."
     */
    String reminderText(String title, List<Ending> endings) {
        String parts = endings.stream()
                .map(e -> e.tierName() + " tickets for " + e.categoryName() + " (" + e.price().toPlainString()
                        + ") end " + WHEN.format(market.atMarketFromUtc(e.endsAtUtc())))
                .collect(Collectors.joining("; "));
        return title + ": " + parts + ". Book on InnBucks before the price goes up.";
    }
}
