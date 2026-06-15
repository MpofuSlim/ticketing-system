package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.service.TicketRenderingService;
import com.innbucks.bookingservice.util.MsisdnCountryResolver;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * Delivers the customer's tickets once a booking is confirmed. Fires on
 * {@link BookingDomainEvent.BookingConfirmed} AFTER the booking transaction
 * commits (idempotent confirm = exactly one event per real confirmation).
 *
 * <p>Two INDEPENDENT, best-effort channels — a failure on either never affects
 * the committed booking:
 * <ul>
 *   <li><b>Email</b> (to the booking's {@code userEmail}, if present) — an HTML
 *       message with each seat's scannable QR, rendered from the hosted ticket
 *       endpoint ({@code /bookings/{id}/tickets/{tn}/qr}) so it shows in Gmail/
 *       Outlook (data-URIs are stripped), plus a link to the ticket page.</li>
 *   <li><b>WhatsApp (to {@code phoneNumber}, if present)</b> — two parts:
 *       <ol>
 *         <li>a plain text confirmation (booking ref, ticket count/numbers,
 *             total). <b>Country-aware routing</b>: foreign MSISDNs (different
 *             country prefix from this deployment) skip the per-country SMS
 *             gateway entirely and use WhatsApp only — the SMS gateways accept
 *             a foreign number with a 2xx and silently drop it, so an SMS
 *             fallback would "succeed" without reaching the customer. Same
 *             pattern as {@code OtpService}.</li>
 *         <li>one scannable QR e-ticket image per booking item, fetched by the
 *             gateway from our public CONFIRMED-only ticket-QR endpoint.</li>
 *       </ol></li>
 * </ul>
 */
@Component
@Slf4j
public class BookingConfirmedNotificationListener {

    private final BookingRepository bookingRepository;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final EmailNotificationClient email;
    private final TicketRenderingService ticketRendering;
    private final EventServiceClient eventServiceClient;
    private final String publicBaseUrl;
    private final String deploymentCountry;

    /** eventName fallback when the event title can't be resolved (event-service down). */
    private static final String EVENT_NAME_FALLBACK = "your event";

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            WhatsAppNotificationClient whatsApp,
            SmsNotificationClient sms,
            EmailNotificationClient email,
            TicketRenderingService ticketRendering,
            EventServiceClient eventServiceClient,
            @Value("${innbucks.tickets.public-base-url:}") String publicBaseUrl,
            @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.bookingRepository = bookingRepository;
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.email = email;
        this.ticketRendering = ticketRendering;
        this.eventServiceClient = eventServiceClient;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.deploymentCountry = deploymentCountry == null ? "" : deploymentCountry.trim();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingConfirmed(BookingDomainEvent.BookingConfirmed event) {
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("BookingConfirmed listener: booking not found bookingId={} — skipping notifications",
                    event.bookingId());
            return;
        }
        boolean anyChannel = false;

        // ---- Email (independent best-effort) ----
        String emailAddr = booking.getUserEmail();
        if (emailAddr != null && !emailAddr.isBlank()) {
            anyChannel = true;
            try {
                email.sendHtmlEmail(emailAddr,
                        "Your InnBucks tickets — booking " + booking.getConfirmationNumber(),
                        ticketRendering.confirmationEmailHtml(booking, publicBaseUrl),
                        "BOOKING-CONFIRM-" + booking.getId());
                log.info("Booking-confirm email sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm email failed bookingId={} (booking still CONFIRMED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        // ---- WhatsApp text confirm (+ domestic-only SMS fallback), then QR e-tickets ----
        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            anyChannel = true;
            // 1) Country-aware text confirmation: foreign MSISDNs skip the
            //    per-country SMS gateway (it 2xxs then silently drops them).
            dispatchPhoneChannel(booking, phone);
            // 2) ADDITIONALLY, one scannable QR e-ticket image per ticket over
            //    WhatsApp — independent best-effort; a failure here never blocks
            //    the text confirmation or email already delivered above.
            sendQrETickets(booking, phone);
        }

        if (!anyChannel) {
            log.warn("BookingConfirmed listener: no email or phone on booking {} — no ticket delivery channel",
                    booking.getConfirmationNumber());
        }
    }

    /**
     * Text confirmation over WhatsApp, with an SMS fallback that runs ONLY for
     * domestic MSISDNs. The per-country SMS gateway silently drops foreign
     * numbers after a 2xx, so for a foreign MSISDN we stop at WhatsApp rather
     * than emit a fake "SMS delivered" the customer never receives.
     */
    private void dispatchPhoneChannel(Booking booking, String phone) {
        boolean domestic = isDomesticMsisdn(phone);
        String reference = "BOOKING-CONFIRM-" + booking.getId();
        try {
            whatsApp.sendCustomNotification(phone, buildWhatsAppMessage(booking));
            log.info("Booking-confirm WhatsApp sent bookingId={} ref={} domestic={}",
                    booking.getId(), booking.getConfirmationNumber(), domestic);
            return;
        } catch (RuntimeException waEx) {
            log.warn("Booking-confirm WhatsApp failed bookingId={} domestic={}: {}",
                    booking.getId(), domestic, waEx.getMessage());
        }

        // SMS fallback is per-country and silently drops foreign MSISDNs after
        // a 2xx. Skipping it for foreign numbers prevents a fake "delivered"
        // log line; the customer would never receive the message.
        if (!domestic) {
            log.warn("Booking-confirm delivery exhausted for foreign MSISDN on {} deployment phone={} bookingId={} — SMS would be dropped, not attempted",
                    deploymentCountry, MsisdnMasking.mask(phone), booking.getId());
            return;
        }

        try {
            sms.sendSms(phone, buildSmsMessage(booking), reference);
            log.info("Booking-confirm SMS sent bookingId={} ref={}",
                    booking.getId(), booking.getConfirmationNumber());
        } catch (RuntimeException smsEx) {
            log.warn("Booking-confirm SMS also failed bookingId={} (booking still CONFIRMED): {}",
                    booking.getId(), smsEx.getMessage());
        }
    }

    /**
     * Sends one scannable QR e-ticket per booking item to the customer's
     * WhatsApp. Each call is independent best-effort — a rejection on one image
     * is logged and skipped so the rest still go out. The qrCodePath is the
     * public hosted ticket-QR endpoint (domain-relative; the gateway prepends
     * its BASE_URL), which only serves CONFIRMED bookings.
     */
    private void sendQrETickets(Booking booking, String phone) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        if (items.isEmpty()) {
            return;
        }
        String eventName = resolveEventName(booking);
        int sent = 0;
        for (BookingItem item : items) {
            String tn = item.getTicketNumber();
            if (tn == null || tn.isBlank()) {
                continue;
            }
            String qrCodePath = "/bookings/" + booking.getId() + "/tickets/" + tn + "/qr";
            try {
                whatsApp.sendEventQrCode(phone, eventName, qrCodePath);
                sent++;
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm QR e-ticket failed bookingId={} ticket={} "
                                + "(other channels/tickets unaffected): {}",
                        booking.getId(), tn, ex.getMessage());
            }
        }
        if (sent > 0) {
            log.info("Booking-confirm QR e-tickets sent bookingId={} ref={} count={}/{}",
                    booking.getId(), booking.getConfirmationNumber(), sent, items.size());
        }
    }

    /**
     * Resolves the event's display name for the e-ticket message. Best-effort —
     * event-service is circuit-broken, and the event name is cosmetic copy, not
     * gate-critical data, so a lookup failure degrades to a generic fallback
     * rather than dropping the QR delivery.
     */
    private String resolveEventName(Booking booking) {
        if (booking.getEventId() == null) {
            return EVENT_NAME_FALLBACK;
        }
        try {
            ApiResult<EventLookupDTO> resp = eventServiceClient.getEvent(booking.getEventId());
            if (resp != null && resp.getData() != null) {
                String title = resp.getData().getTitle();
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Event name lookup failed bookingId={} eventId={} — using fallback: {}",
                    booking.getId(), booking.getEventId(), ex.getMessage());
        }
        return EVENT_NAME_FALLBACK;
    }

    /**
     * True when the MSISDN's dialling prefix matches the deployment country.
     * An unresolvable MSISDN (unknown prefix) is treated as non-domestic —
     * safer to skip the per-country SMS gateway than claim a delivery the
     * gateway will silently drop.
     */
    private boolean isDomesticMsisdn(String phoneNumber) {
        return MsisdnCountryResolver.resolve(phoneNumber)
                .map(c -> c.equalsIgnoreCase(deploymentCountry))
                .orElse(false);
    }

    private String buildWhatsAppMessage(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("InnBucks: your booking ")
                .append(booking.getConfirmationNumber())
                .append(" is confirmed");
        if (items.size() == 1) {
            sb.append(" — 1 ticket");
        } else if (items.size() > 1) {
            sb.append(" — ").append(items.size()).append(" tickets");
        }
        appendTotal(sb, booking);
        sb.append('.');
        // No links by product direction — just the ticket number(s) as the
        // manual reference at the gate. The QR reaches the customer via email.
        if (!items.isEmpty()) {
            sb.append(" Ticket number").append(items.size() > 1 ? "s" : "").append(": ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i).getTicketNumber());
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private String buildSmsMessage(Booking booking) {
        // SMS fallback — one short segment, no links (product direction).
        StringBuilder sb = new StringBuilder("InnBucks: booking ")
                .append(booking.getConfirmationNumber())
                .append(" confirmed");
        int n = booking.getItems() == null ? 0 : booking.getItems().size();
        if (n == 1) {
            sb.append(" (1 ticket)");
        } else if (n > 1) {
            sb.append(" (").append(n).append(" tickets)");
        }
        appendTotal(sb, booking);
        sb.append('.');
        return sb.toString();
    }

    private static void appendTotal(StringBuilder sb, Booking booking) {
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
    }
}
