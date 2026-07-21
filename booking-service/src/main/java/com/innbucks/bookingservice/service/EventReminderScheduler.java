package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
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
 * Sends each CONFIRMED booking one pre-event WhatsApp reminder when its event
 * starts within the reminder window (default 24h). The e-tickets were already
 * delivered at confirmation time; this is the "see you tomorrow" nudge that
 * cuts no-shows, so it is WhatsApp-only (the customer channel) — no email.
 *
 * <p>Runs hourly under ShedLock. Each booking is reminded AT MOST ONCE:
 * {@code reminderSentAt} is stamped on the attempt (success or failure —
 * best-effort beats retry spam) and stamped silently for bookings whose event
 * already started, so old rows drop out of the scan instead of being
 * re-examined every tick. The scan is bounded by the V18 partial index.
 *
 * <p>The event's start time is not stored on bookings; it is resolved per
 * event via the existing {@link EventServiceClient} Feign lookup (circuit-
 * breaker fallback returns a null payload, which simply defers that event to
 * the next tick).
 */
@Service
@Slf4j
public class EventReminderScheduler {

    private static final DateTimeFormatter START_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm");

    private final BookingRepository bookingRepository;
    private final EventServiceClient eventServiceClient;
    private final WhatsAppNotificationClient whatsApp;
    private final Duration reminderWindow;

    public EventReminderScheduler(BookingRepository bookingRepository,
                                  EventServiceClient eventServiceClient,
                                  WhatsAppNotificationClient whatsApp,
                                  @Value("${app.booking.reminder-window-hours:24}") long reminderWindowHours) {
        this.bookingRepository = bookingRepository;
        this.eventServiceClient = eventServiceClient;
        this.whatsApp = whatsApp;
        this.reminderWindow = Duration.ofHours(reminderWindowHours);
    }

    @Scheduled(cron = "${app.booking.reminder-cron:0 10 * * * *}", zone = "UTC")
    @SchedulerLock(name = "EventReminderScheduler.remind", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Transactional
    public void remind() {
        List<UUID> eventIds = bookingRepository.findEventIdsWithUnremindedConfirmed();
        if (eventIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (UUID eventId : eventIds) {
            try {
                processEvent(eventId, now);
            } catch (RuntimeException e) {
                // Best-effort per event — one bad event never blocks the rest.
                log.warn("Event-reminder pass failed for eventId={}: {}", eventId, e.toString());
            }
        }
    }

    private void processEvent(UUID eventId, LocalDateTime now) {
        ApiResult<EventLookupDTO> res = eventServiceClient.getEvent(eventId);
        EventLookupDTO event = res == null ? null : res.getData();
        if (event == null || event.getStartDateTime() == null) {
            // event-service down (fallback) or start unknown — retry next tick.
            log.debug("Event-reminder: no start time for eventId={} — deferring", eventId);
            return;
        }
        LocalDateTime start = event.getStartDateTime();
        if (start.isBefore(now)) {
            // Already started — consume the marker silently so these bookings
            // stop being rescanned every hour forever.
            consumeSilently(eventId, now);
            return;
        }
        if (start.isAfter(now.plus(reminderWindow))) {
            // Not yet in the window — nothing to do this tick.
            return;
        }
        sendReminders(eventId, event, now);
    }

    private void sendReminders(UUID eventId, EventLookupDTO event, LocalDateTime now) {
        List<Booking> bookings = bookingRepository.findByEventIdAndStatusAndReminderSentAtIsNull(
                eventId, Booking.BookingStatus.CONFIRMED);
        int sent = 0;
        for (Booking booking : bookings) {
            String phone = booking.getPhoneNumber();
            if (phone != null && !phone.isBlank()) {
                try {
                    whatsApp.sendCustomNotification(phone, reminderText(event, booking));
                    sent++;
                } catch (RuntimeException e) {
                    log.warn("Event-reminder WhatsApp failed bookingId={} (marked reminded anyway): {}",
                            booking.getId(), e.getMessage());
                }
            }
            booking.setReminderSentAt(now);
        }
        bookingRepository.saveAll(bookings);
        log.info("Event reminders sent eventId={} title=\"{}\" sent={}/{}",
                eventId, event.getTitle(), sent, bookings.size());
    }

    private void consumeSilently(UUID eventId, LocalDateTime now) {
        List<Booking> bookings = bookingRepository.findByEventIdAndStatusAndReminderSentAtIsNull(
                eventId, Booking.BookingStatus.CONFIRMED);
        bookings.forEach(b -> b.setReminderSentAt(now));
        bookingRepository.saveAll(bookings);
        log.debug("Event-reminder: eventId={} already started — {} booking(s) marked without sending",
                eventId, bookings.size());
    }

    private static String reminderText(EventLookupDTO event, Booking booking) {
        String title = event.getTitle() == null || event.getTitle().isBlank()
                ? "your event" : event.getTitle();
        return "Reminder: " + title + " starts on "
                + START_FMT.format(event.getStartDateTime())
                + ". Your e-ticket(s) were sent when you booked (confirmation "
                + booking.getConfirmationNumber() + "). See you there!";
    }
}
