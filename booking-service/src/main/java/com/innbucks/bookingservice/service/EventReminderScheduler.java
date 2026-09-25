package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Sends each CONFIRMED booking TWO pre-event reminders:
 *
 * <ul>
 *   <li><b>T-2 days</b> ({@code reminder2dSentAt}, V19) — SMS + email, the
 *       "coming up this weekend" heads-up;</li>
 *   <li><b>day-of</b> ({@code reminderSentAt}, V18, default 24h window) —
 *       SMS + email + the original WhatsApp nudge.</li>
 * </ul>
 *
 * <p>Runs hourly under ShedLock. Each stage fires AT MOST ONCE per booking:
 * its marker is stamped on the attempt (success or failure — best-effort
 * beats retry spam) and stamped silently when the send would be pointless or
 * spammy: events already started, and — for the 2-day stage — bookings that
 * only entered the scan once the day-of window was already reached (a
 * customer who books the night before gets ONE reminder, not two
 * back-to-back). Scans are bounded by the V18/V19 partial indexes.
 *
 * <p>Channels are independent best-effort per booking: a failed SMS never
 * blocks the email, and vice versa. The event's start time is resolved per
 * event via the existing {@link EventServiceClient} Feign lookup (circuit-
 * breaker fallback returns a null payload, which simply defers that event to
 * the next tick).
 *
 * <p><b>One text for WhatsApp and SMS, and it names the date, never a
 * relative day.</b> The SMS used to say "is today, on Sat 26 Sep" for anything
 * inside the 24h day-of window — so an event on Saturday morning was announced
 * as "today" on Friday. "In 2 days" had the same flaw for anything 25-48h out.
 * The WhatsApp copy never had the problem because it only ever stated the
 * date; every channel now reuses it ({@link #reminderText}).
 *
 * <p><b>The time is the market's wall clock</b>, rendered by
 * {@link MarketTimeZone} from the stored UTC start — a customer reads this
 * text verbatim, and the raw UTC digits read two hours early in Harare
 * (same rule as the scan reports: the BE renders, the reader parses nothing).
 */
@Service
@Slf4j
public class EventReminderScheduler {

    private static final DateTimeFormatter START_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm");

    private final BookingRepository bookingRepository;
    private final EventServiceClient eventServiceClient;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final EmailNotificationClient email;
    private final MarketTimeZone market;
    private final Duration dayOfWindow;
    private final Duration twoDayWindow;

    public EventReminderScheduler(BookingRepository bookingRepository,
                                  EventServiceClient eventServiceClient,
                                  WhatsAppNotificationClient whatsApp,
                                  SmsNotificationClient sms,
                                  EmailNotificationClient email,
                                  MarketTimeZone market,
                                  @Value("${app.booking.reminder-window-hours:24}") long dayOfWindowHours,
                                  @Value("${app.booking.reminder-2d-window-hours:48}") long twoDayWindowHours) {
        this.bookingRepository = bookingRepository;
        this.eventServiceClient = eventServiceClient;
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.email = email;
        this.market = market;
        this.dayOfWindow = Duration.ofHours(dayOfWindowHours);
        this.twoDayWindow = Duration.ofHours(twoDayWindowHours);
    }

    @Scheduled(cron = "${app.booking.reminder-cron:0 10 * * * *}", zone = "UTC")
    @SchedulerLock(name = "EventReminderScheduler.remind", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Transactional
    public void remind() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 2-day stage first: a booking already inside the day-of window gets
        // its 2d marker consumed silently in this pass, then the day-of pass
        // sends the single reminder it should receive.
        for (UUID eventId : bookingRepository.findEventIdsWithUn2dRemindedConfirmed()) {
            try {
                processTwoDayStage(eventId, now);
            } catch (RuntimeException e) {
                // Best-effort per event — one bad event never blocks the rest.
                log.warn("2-day reminder pass failed for eventId={}: {}", eventId, e.toString());
            }
        }
        for (UUID eventId : bookingRepository.findEventIdsWithUnremindedConfirmed()) {
            try {
                processDayOfStage(eventId, now);
            } catch (RuntimeException e) {
                log.warn("Day-of reminder pass failed for eventId={}: {}", eventId, e.toString());
            }
        }
    }

    // ---- T-2-days stage (SMS + email) -------------------------------------

    private void processTwoDayStage(UUID eventId, LocalDateTime now) {
        EventLookupDTO event = lookupEvent(eventId);
        if (event == null) {
            return; // event-service down or start unknown — retry next tick.
        }
        LocalDateTime start = event.getStartDateTime();
        if (start.isAfter(now.plus(twoDayWindow))) {
            return; // not yet in the window — nothing to do this tick.
        }
        boolean sendable = start.isAfter(now.plus(dayOfWindow));
        List<Booking> bookings = bookingRepository.findByEventIdAndStatusAndReminder2dSentAtIsNull(
                eventId, Booking.BookingStatus.CONFIRMED);
        int sent = 0;
        for (Booking booking : bookings) {
            if (sendable) {
                boolean any = deliver(booking, event, start,
                        "RMD-2D-" + booking.getConfirmationNumber());
                if (any) sent++;
            }
            booking.setReminder2dSentAt(now);
        }
        bookingRepository.saveAll(bookings);
        if (sendable) {
            log.info("2-day event reminders sent eventId={} title=\"{}\" sent={}/{}",
                    eventId, event.getTitle(), sent, bookings.size());
        } else {
            // Started, or already inside the day-of window (late bookings):
            // consume silently so the day-of stage is the one reminder.
            log.debug("2-day reminder: eventId={} inside day-of window/started — {} booking(s) marked without sending",
                    eventId, bookings.size());
        }
    }

    // ---- day-of stage (WhatsApp + SMS + email) -----------------------------

    private void processDayOfStage(UUID eventId, LocalDateTime now) {
        EventLookupDTO event = lookupEvent(eventId);
        if (event == null) {
            return;
        }
        LocalDateTime start = event.getStartDateTime();
        if (start.isBefore(now)) {
            // Already started — consume the marker silently so these bookings
            // stop being rescanned every hour forever.
            consumeDayOfSilently(eventId, now);
            return;
        }
        if (start.isAfter(now.plus(dayOfWindow))) {
            return;
        }
        List<Booking> bookings = bookingRepository.findByEventIdAndStatusAndReminderSentAtIsNull(
                eventId, Booking.BookingStatus.CONFIRMED);
        int sent = 0;
        for (Booking booking : bookings) {
            boolean any = deliver(booking, event, start,
                    "RMD-DAY-" + booking.getConfirmationNumber());
            String phone = booking.getPhoneNumber();
            if (phone != null && !phone.isBlank()) {
                try {
                    whatsApp.sendCustomNotification(phone, reminderText(titleOf(event), booking, when(start)));
                    any = true;
                } catch (RuntimeException e) {
                    log.warn("Event-reminder WhatsApp failed bookingId={} (marked reminded anyway): {}",
                            booking.getId(), e.getMessage());
                }
            }
            if (any) sent++;
            booking.setReminderSentAt(now);
        }
        bookingRepository.saveAll(bookings);
        log.info("Day-of event reminders sent eventId={} title=\"{}\" sent={}/{}",
                eventId, event.getTitle(), sent, bookings.size());
    }

    private void consumeDayOfSilently(UUID eventId, LocalDateTime now) {
        List<Booking> bookings = bookingRepository.findByEventIdAndStatusAndReminderSentAtIsNull(
                eventId, Booking.BookingStatus.CONFIRMED);
        bookings.forEach(b -> b.setReminderSentAt(now));
        bookingRepository.saveAll(bookings);
        log.debug("Day-of reminder: eventId={} already started — {} booking(s) marked without sending",
                eventId, bookings.size());
    }

    // ---- shared delivery ----------------------------------------------------

    /**
     * SMS + email for one booking, independent best-effort per channel.
     * Returns whether at least one channel accepted the send.
     * {@code reference} is the notification-API reference — per-channel
     * suffixed, and the email client clamps it to the API's 46-char cap.
     * All copy is deliberately plain ASCII (the notification API rejects
     * non-ASCII subjects, and GSM-unsafe SMS chars cost message parts).
     */
    private boolean deliver(Booking booking, EventLookupDTO event,
                            LocalDateTime start, String reference) {
        String title = titleOf(event);
        String when = when(start);
        boolean any = false;
        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            try {
                // Same text as the WhatsApp reminder, word for word. It is
                // holder-neutral ("the e-ticket(s) were sent"), which matters
                // since per-holder routing (V22): a guest's QR may live on the
                // guest's phone, not this (purchaser's) one.
                sms.sendSms(phone, reminderText(title, booking, when), reference + "-S");
                any = true;
            } catch (RuntimeException e) {
                log.warn("Event-reminder SMS failed bookingId={} (marker still stamped): {}",
                        booking.getId(), e.getMessage());
            }
        }
        String emailAddr = booking.getUserEmail();
        if (emailAddr != null && !emailAddr.isBlank()) {
            try {
                email.sendEmail(emailAddr,
                        "Reminder: your event starts on " + when + " - booking " + booking.getConfirmationNumber(),
                        emailBody(booking, title, when),
                        reference + "-E");
                any = true;
            } catch (RuntimeException e) {
                log.warn("Event-reminder email failed bookingId={} (marker still stamped): {}",
                        booking.getId(), e.getMessage());
            }
        }
        return any;
    }

    private static String emailBody(Booking booking, String title, String when) {
        return "Hi!\n\n"
                + "This is a reminder that " + title + " starts on " + when + ".\n\n"
                + "Booking reference: " + booking.getConfirmationNumber() + "\n\n"
                + "The scannable e-tickets were sent on WhatsApp when you booked - your own "
                + "to you, and any named guest's directly to them. Present the QR at the "
                + "gate. Need one again? Ask the organizer to resend it.\n\n"
                + "See you there!";
    }

    /** The event start as the market's wall clock, e.g. "Sat 26 Sep 2026 at 10:00". */
    String when(LocalDateTime utcStart) {
        return START_FMT.format(market.atMarketFromUtc(utcStart));
    }

    private EventLookupDTO lookupEvent(UUID eventId) {
        ApiResult<EventLookupDTO> res = eventServiceClient.getEvent(eventId);
        EventLookupDTO event = res == null ? null : res.getData();
        if (event == null || event.getStartDateTime() == null) {
            log.debug("Event-reminder: no start time for eventId={} — deferring", eventId);
            return null;
        }
        return event;
    }

    private static String titleOf(EventLookupDTO event) {
        return event.getTitle() == null || event.getTitle().isBlank() ? "your event" : event.getTitle();
    }

    /** The reminder text — sent verbatim on both WhatsApp and SMS. */
    static String reminderText(String title, Booking booking, String when) {
        return "Reminder: " + title + " starts on " + when
                + ". The e-ticket(s) were sent on WhatsApp when you booked (confirmation "
                + booking.getConfirmationNumber() + "). See you there!";
    }
}
