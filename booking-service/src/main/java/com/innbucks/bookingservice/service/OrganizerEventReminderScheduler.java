package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.dto.TenantLookupRequest;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.OrganizerEventReminder;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.OrganizerEventReminderRepository;
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
 * Day-before headline email to the ORGANIZER of each event with confirmed
 * sales: "your event is tomorrow — N bookings, M tickets sold, is your
 * scanning team ready?". Attendees get their own reminders
 * ({@link EventReminderScheduler}); this closes the loop for the people
 * running the gate.
 *
 * <p>Runs hourly under ShedLock, exactly-once per EVENT via the V21 marker
 * table: the row is written on the attempt (success or failure — best-effort
 * beats retry spam) and written silently for events that already started, so
 * old events drop out of the scan. Events only enter the scan once they have
 * at least one CONFIRMED booking; a zero-sales event sends nothing (there is
 * no gate to staff).
 *
 * <p>The organizer's identity comes from the INTERNAL event lookup (the
 * public one strips {@code tenantUserUuid} for anonymous callers) and their
 * business email from user-service's tenant-contact lookup — both existing
 * S2S contracts. Lookup failures defer the event to the next tick without
 * consuming the marker.
 */
@Service
@Slf4j
public class OrganizerEventReminderScheduler {

    private static final DateTimeFormatter START_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm");

    private final BookingRepository bookingRepository;
    private final OrganizerEventReminderRepository markers;
    private final EventServiceClient eventServiceClient;
    private final UserServiceClient userServiceClient;
    private final EmailNotificationClient email;
    private final String internalToken;
    private final Duration window;

    public OrganizerEventReminderScheduler(
            BookingRepository bookingRepository,
            OrganizerEventReminderRepository markers,
            EventServiceClient eventServiceClient,
            UserServiceClient userServiceClient,
            EmailNotificationClient email,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            @Value("${app.booking.organizer-reminder-window-hours:24}") long windowHours) {
        this.bookingRepository = bookingRepository;
        this.markers = markers;
        this.eventServiceClient = eventServiceClient;
        this.userServiceClient = userServiceClient;
        this.email = email;
        this.internalToken = internalToken;
        this.window = Duration.ofHours(windowHours);
    }

    @Scheduled(cron = "${app.booking.organizer-reminder-cron:0 20 * * * *}", zone = "UTC")
    @SchedulerLock(name = "OrganizerEventReminderScheduler.remind", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Transactional
    public void remind() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (UUID eventId : bookingRepository.findEventIdsForOrganizerReminder()) {
            try {
                processEvent(eventId, now);
            } catch (RuntimeException e) {
                // Best-effort per event — one bad event never blocks the rest.
                log.warn("Organizer-reminder pass failed for eventId={}: {}", eventId, e.toString());
            }
        }
    }

    private void processEvent(UUID eventId, LocalDateTime now) {
        ApiResult<EventLookupDTO> res = eventServiceClient.getEventInternal(eventId, internalToken);
        EventLookupDTO event = res == null ? null : res.getData();
        if (event == null || event.getStartDateTime() == null) {
            log.debug("Organizer-reminder: no start time for eventId={} — deferring", eventId);
            return;
        }
        LocalDateTime start = event.getStartDateTime();
        if (start.isBefore(now)) {
            // Already started — consume the marker silently so this event
            // stops being rescanned every hour forever.
            markers.save(new OrganizerEventReminder(eventId, now));
            return;
        }
        if (start.isAfter(now.plus(window))) {
            return; // not yet in the window.
        }
        sendAndMark(eventId, event, start, now);
    }

    private void sendAndMark(UUID eventId, EventLookupDTO event, LocalDateTime start, LocalDateTime now) {
        long confirmedBookings = bookingRepository.countByEventIdAndStatus(
                eventId, Booking.BookingStatus.CONFIRMED);
        long ticketsSold = bookingRepository.countConfirmedTickets(eventId);
        String to = resolveOrganizerEmail(event.getTenantUserUuid());
        if (to != null && !to.isBlank()) {
            String title = event.getTitle() == null || event.getTitle().isBlank()
                    ? "Your event" : event.getTitle();
            try {
                email.sendEmail(to,
                        "Tomorrow: " + title + " - " + ticketsSold + " tickets sold",
                        body(title, start, confirmedBookings, ticketsSold),
                        "ORG-RMD-" + shortId(eventId));
                log.info("Organizer reminder sent eventId={} title=\"{}\" bookings={} tickets={}",
                        eventId, event.getTitle(), confirmedBookings, ticketsSold);
            } catch (RuntimeException e) {
                log.warn("Organizer reminder email failed eventId={} (marker still stamped): {}",
                        eventId, e.getMessage());
            }
        } else {
            log.info("Organizer reminder: no business email for organizer={} eventId={} — marked without send",
                    event.getTenantUserUuid(), eventId);
        }
        markers.save(new OrganizerEventReminder(eventId, now));
    }

    private static String body(String title, LocalDateTime start, long bookings, long tickets) {
        return "Hi,\n\n"
                + title + " starts " + START_FMT.format(start) + " - that's tomorrow!\n\n"
                + "Headline numbers as of now:\n"
                + "  Confirmed bookings: " + bookings + "\n"
                + "  Tickets sold: " + tickets + "\n\n"
                + "Pre-event checklist:\n"
                + "  - Is your scanning team set up and their devices charged?\n"
                + "  - Team members can scan tickets from their dashboard.\n"
                + "  - Live sales and scan reports are in your organizer dashboard.\n\n"
                + "Have a great event!\n\n"
                + "- The InnBucks Team";
    }

    private String resolveOrganizerEmail(UUID organizerUuid) {
        if (organizerUuid == null) {
            return null;
        }
        ApiResult<List<TenantContactDTO>> res = userServiceClient.lookupTenants(
                new TenantLookupRequest(List.of(organizerUuid)), internalToken);
        if (res == null || res.getData() == null) {
            return null;
        }
        return res.getData().stream()
                .filter(t -> organizerUuid.equals(t.userUuid()))
                .map(TenantContactDTO::businessEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
