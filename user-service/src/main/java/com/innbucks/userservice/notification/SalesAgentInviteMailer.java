package com.innbucks.userservice.notification;

import com.innbucks.common.email.BrandedEmailRenderer;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.config.SalesAgentInviteProperties;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.event.SalesAgentInviteRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Emails a sales agent their Foundry portal invite once the transaction that
 * minted it has committed (V56). The {@link StaffInviteMailer} shape: the
 * ordinary email path ({@link EmailNotificationClient#sendEmail}, SES when the
 * cell has it on, else the notification API) with a branded "Set up your
 * sign-in" button, and the link's token in the URL FRAGMENT.
 *
 * <p><b>Never log the link, the token or the body.</b> The delivery reference is
 * {@code SALES-AGENT-INVITE-<inviteId>}, which carries no token, and a failure is
 * logged as that reference and the upstream HTTP status only.
 *
 * <p>Best-effort: event-service has already answered the organizer 202. A
 * failure is counted on {@code user.sales_agent_invite.delivery{outcome}} and
 * logged; the organizer re-sends from the portal (a new invite revokes this one).
 */
@Slf4j
@Component
public class SalesAgentInviteMailer {

    public static final String SUBJECT = "Sell tickets on the InnBucks Foundry portal";
    public static final String METRIC = "user.sales_agent_invite.delivery";
    static final String BUTTON_LABEL = "Set up your sign-in";
    static final String UNKNOWN_ORGANIZER = "An event organiser";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailNotificationClient email;
    private final StaffAccountProperties staffProperties;
    private final SalesAgentInviteProperties properties;
    private final MarketTimeZone marketTimeZone;
    private MeterRegistry meters;

    public SalesAgentInviteMailer(EmailNotificationClient email, StaffAccountProperties staffProperties,
                                  SalesAgentInviteProperties properties, MarketTimeZone marketTimeZone) {
        this.email = email;
        this.staffProperties = staffProperties;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
    }

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meters) {
        this.meters = meters;
        if (meters != null) {
            for (String outcome : new String[]{"sent", "failed"}) {
                Counter.builder(METRIC).description("Sales-agent portal invite emails by outcome")
                        .tag("outcome", outcome).register(meters);
            }
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInviteRequested(SalesAgentInviteRequested event) {
        deliver(event);
    }

    /** The delivery itself, synchronous; {@link #onInviteRequested} is its async, after-commit entry. Never throws. */
    public boolean deliver(SalesAgentInviteRequested event) {
        String reference = "SALES-AGENT-INVITE-" + event.inviteId();
        boolean sent;
        try {
            String link = link(event.rawToken());
            email.sendEmail(event.to(), SUBJECT, body(event, link), reference,
                    new BrandedEmailRenderer.CallToAction(BUTTON_LABEL, link));
            sent = true;
            log.info("Sales-agent invite delivered ref={}", reference);
        } catch (NotificationDeliveryException ex) {
            sent = false;
            log.warn("Sales-agent invite delivery failed ref={} status={}", reference,
                    StaffInviteMailer.upstreamStatus(ex));
        } catch (RuntimeException ex) {
            sent = false;
            log.warn("Sales-agent invite delivery failed ref={} error={}", reference, ex.getClass().getSimpleName());
        }
        if (meters != null) {
            meters.counter(METRIC, "outcome", sent ? "sent" : "failed").increment();
        }
        return sent;
    }

    /** {@code <console><path>#token=SAI-…}: the token in the FRAGMENT, never sent to a server by a browser. */
    String link(String rawToken) {
        return staffProperties.consoleBaseUrl() + properties.getPath() + "#token=" + rawToken;
    }

    /** Plain-text copy; the branded HTML renders the same text plus the button. */
    String body(SalesAgentInviteRequested event, String link) {
        String organizer = event.organizerName() == null || event.organizerName().isBlank()
                ? UNKNOWN_ORGANIZER : event.organizerName();
        return "Hi,\n\n"
                + organizer + " has asked you to sell tickets for them on the InnBucks Foundry portal.\n\n"
                + "Set up your sign-in here:\n" + link + "\n\n"
                + "We'll send a code to your phone to check it's you, then you choose a password. "
                + "You'll set up two-step verification when you first sign in. "
                + "The link works once and expires at " + expiry(event.expiresAt()) + ".\n\n"
                + "If you don't know this organiser, ignore this email.";
    }

    /** "10.15 on 2 Oct", in the market's local time. */
    String expiry(LocalDateTime utc) {
        if (utc == null) return "the time shown in the portal";
        OffsetDateTime local = marketTimeZone.atMarketFromUtc(utc);
        return TIME.format(local) + " on " + DAY.format(local);
    }
}
