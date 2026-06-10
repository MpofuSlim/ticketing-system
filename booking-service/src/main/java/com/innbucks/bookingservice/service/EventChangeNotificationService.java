package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fans a "your event changed / was cancelled" notification out to every
 * CONFIRMED attendee of an event. Triggered by event-service via the internal
 * endpoint on {@link com.innbucks.bookingservice.controller.BookingController}.
 *
 * <p>Channel order: <b>SMS primary → WhatsApp fallback</b>, per recipient. SMS
 * is the universal reach for a one-way alert like this; WhatsApp catches anyone
 * the SMS gateway couldn't deliver to.
 *
 * <p>Runs {@link Async} so the internal HTTP call returns 202 immediately — a
 * popular event could have hundreds of attendees and we must not hold the
 * caller (or event-service's request) open for the whole fan-out. Best-effort
 * per recipient: one customer's gateway failure is logged and the loop
 * continues, so a single bad number never blocks the rest of the broadcast.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventChangeNotificationService {

    private final BookingRepository bookingRepository;
    private final SmsNotificationClient smsNotificationClient;
    private final WhatsAppNotificationClient whatsAppNotificationClient;

    @Async
    @Transactional(readOnly = true)
    public void broadcast(UUID eventId, String changeType, String eventTitle,
                          String newStartDateTime, String newVenue) {
        List<Booking> attendees =
                bookingRepository.findByEventIdAndStatus(eventId, Booking.BookingStatus.CONFIRMED);
        if (attendees.isEmpty()) {
            log.info("Event-change broadcast: no confirmed bookings for eventId={} changeType={} — nothing to send",
                    eventId, changeType);
            return;
        }

        boolean cancelled = BookingNotificationCopy.isCancelled(changeType);
        int sent = 0, skipped = 0, failed = 0;
        for (Booking b : attendees) {
            String phone = b.getPhoneNumber();
            if (phone == null || phone.isBlank()) {
                skipped++;
                continue;
            }
            String message = cancelled
                    ? BookingNotificationCopy.cancelled(eventTitle, b.getConfirmationNumber())
                    : BookingNotificationCopy.updated(eventTitle, newStartDateTime, newVenue,
                            b.getConfirmationNumber());
            if (deliver(phone, message, b)) {
                sent++;
            } else {
                failed++;
            }
        }
        log.info("Event-change broadcast done eventId={} changeType={} confirmed={} sent={} skipped(noPhone)={} failed={}",
                eventId, changeType, attendees.size(), sent, skipped, failed);
    }

    /** SMS first, WhatsApp on SMS failure. Best-effort — returns false only if both fail. */
    private boolean deliver(String phone, String message, Booking b) {
        try {
            smsNotificationClient.sendSms(phone, message, "EVENT-CHANGE-" + b.getId());
            return true;
        } catch (RuntimeException smsEx) {
            log.warn("Event-change SMS failed bookingId={}, trying WhatsApp: {}", b.getId(), smsEx.getMessage());
        }
        try {
            whatsAppNotificationClient.sendCustomNotification(phone, message);
            return true;
        } catch (RuntimeException waEx) {
            log.warn("Event-change notification failed bookingId={} (both channels): {}",
                    b.getId(), waEx.getMessage());
            return false;
        }
    }

    /** Message copy, split out so it's unit-testable without the async/tx machinery. */
    static final class BookingNotificationCopy {
        private BookingNotificationCopy() {}

        static boolean isCancelled(String changeType) {
            return "CANCELLED".equalsIgnoreCase(changeType);
        }

        static String cancelled(String eventTitle, String confirmationNumber) {
            return "InnBucks: we're sorry — the event \"" + safe(eventTitle)
                    + "\" has been CANCELLED. Your booking " + safe(confirmationNumber)
                    + " is affected; if you paid, a refund will be processed and our team will be in touch.";
        }

        static String updated(String eventTitle, String newStartDateTime, String newVenue,
                              String confirmationNumber) {
            StringBuilder sb = new StringBuilder("InnBucks: the event \"")
                    .append(safe(eventTitle)).append("\" has been updated.");
            if (newStartDateTime != null && !newStartDateTime.isBlank()) {
                sb.append(" New date/time: ").append(newStartDateTime).append('.');
            }
            if (newVenue != null && !newVenue.isBlank()) {
                sb.append(" New venue: ").append(newVenue).append('.');
            }
            sb.append(" Booking ref: ").append(safe(confirmationNumber))
                    .append(". See the InnBucks app for details.");
            return sb.toString();
        }

        private static String safe(String s) {
            return s == null ? "" : s;
        }
    }
}
