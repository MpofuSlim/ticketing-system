package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.util.MsisdnMasking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a person an organizer added them as gate staff (event-service V14
 * scanners): WhatsApp first, SMS when WhatsApp fails. The invite itself lives
 * in the super app ({@code GET /events/gate}); this message is only what makes
 * the person open it.
 *
 * <p><b>The wording is fixed here.</b> The internal token is shared by every
 * service, so the endpoint that calls this takes the organizer's name and the
 * event's title and nothing else: a caller can never put a sentence of its own,
 * or a link, in front of a stranger. Both names are flattened to one line and
 * capped (a line break would let a crafted title forge a second paragraph).
 *
 * <p><b>Nothing in it says whether the number has an InnBucks account</b>, and
 * the organizer never learns whether it was delivered. The last sentence is for
 * a stranger whose number was typed by mistake.
 *
 * <p>Best-effort and asynchronous: the caller has already committed the
 * assignment, a dead gateway costs the message, never the invite. Counted on
 * {@code user.scanner_invite.message{outcome=whatsapp|sms|failed}}, registered
 * at 0.
 */
@Component
@Slf4j
public class ScannerInviteNotifier {

    static final String METRIC = "user.scanner_invite.message";
    static final int MAX_ORGANIZER_NAME = 80;
    static final int MAX_EVENT_TITLE = 100;
    static final String UNKNOWN_ORGANIZER = "An event organiser";

    /** Character types that never reach a message: they become a space. */
    private static final List<Integer> FLATTENED = List.of(
            (int) Character.CONTROL, (int) Character.FORMAT,
            (int) Character.LINE_SEPARATOR, (int) Character.PARAGRAPH_SEPARATOR,
            (int) Character.PRIVATE_USE, (int) Character.UNASSIGNED, (int) Character.SURROGATE);

    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final Map<String, Counter> outcomes;

    public ScannerInviteNotifier(WhatsAppNotificationClient whatsApp, SmsNotificationClient sms,
                                 MeterRegistry meters) {
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.outcomes = Map.of(
                "whatsapp", counter(meters, "whatsapp"),
                "sms", counter(meters, "sms"),
                "failed", counter(meters, "failed"));
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder(METRIC)
                .description("Gate-scanner invite messages by the channel that delivered them")
                .tag("outcome", outcome)
                .register(meters);
    }

    /**
     * Sends the invite message to {@code phone} (E.164). Never throws.
     *
     * @param organizerName the organizer's business name; null or blank reads "An event organiser"
     * @param eventTitle    the event the person was added for; null or blank leaves it out
     * @param scannerId     names the delivery reference only
     */
    @Async("notificationExecutor")
    public void send(String phone, String organizerName, String eventTitle, UUID scannerId) {
        String text = message(organizerName, eventTitle);
        String reference = "SCANNER-INVITE-" + (scannerId == null ? UUID.randomUUID() : scannerId);
        try {
            whatsApp.sendCustomNotification(phone, text);
            outcomes.get("whatsapp").increment();
            log.info("Scanner invite sent by WhatsApp to={} ref={}", MsisdnMasking.mask(phone), reference);
            return;
        } catch (RuntimeException e) {
            log.warn("Scanner invite WhatsApp failed, trying SMS to={} ref={} cause={}",
                    MsisdnMasking.mask(phone), reference, e.getMessage());
        }
        try {
            sms.sendSms(phone, text, reference);
            outcomes.get("sms").increment();
            log.info("Scanner invite sent by SMS to={} ref={}", MsisdnMasking.mask(phone), reference);
        } catch (RuntimeException e) {
            outcomes.get("failed").increment();
            log.warn("Scanner invite not delivered to={} ref={} cause={}",
                    MsisdnMasking.mask(phone), reference, e.getMessage());
        }
    }

    /**
     * The one message, e.g. "Harare Arena Events added you as gate staff for Jazz
     * on the Lawn. Open the InnBucks app to accept, then scan tickets there on the
     * day. If you don't know this organiser, ignore this message."
     *
     * <p>No colon, slash, question mark, semicolon, star, exclamation mark or
     * double quote in the fixed words: the SMS gateway refuses all seven, and
     * the same text goes to both channels.
     */
    static String message(String organizerName, String eventTitle) {
        String organizer = singleLine(organizerName, MAX_ORGANIZER_NAME);
        String event = singleLine(eventTitle, MAX_EVENT_TITLE);
        String who = organizer == null ? UNKNOWN_ORGANIZER : organizer;
        String what = event == null ? " on InnBucks" : " for " + event;
        return who + " added you as gate staff" + what
                + ". Open the InnBucks app to accept, then scan tickets there on the day."
                + " If you don't know this organiser, ignore this message.";
    }

    /**
     * One line: every control, format (bidi override, zero-width) and
     * line/paragraph separator character becomes a space, runs of spaces become
     * one, and the result is capped. Null or nothing left is null.
     */
    static String singleLine(String value, int max) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean blank = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || FLATTENED.contains(type);
            out.appendCodePoint(blank ? ' ' : cp);
        });
        String flat = out.toString().replaceAll(" {2,}", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }
        if (flat.length() <= max) {
            return flat;
        }
        int end = max;
        if (Character.isHighSurrogate(flat.charAt(end - 1))) {
            end--;
        }
        return flat.substring(0, end).trim();
    }
}
