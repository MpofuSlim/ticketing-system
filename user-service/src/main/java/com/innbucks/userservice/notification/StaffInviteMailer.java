package com.innbucks.userservice.notification;

import com.innbucks.common.email.BrandedEmailRenderer;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.event.StaffInviteRequested;
import com.innbucks.userservice.repository.StaffInviteRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Emails a staff invite once the transaction that minted it has committed (V44).
 *
 * <p><b>The same path as every other email</b> (owner decision, 29 Sept 2026):
 * {@link EmailNotificationClient#sendEmail} — Amazon SES as "Foundry
 * &lt;banking@innbucks.co.zw&gt;" when the cell runs {@code MAIL_ENABLED=true},
 * the InnBucks notification API as fallback. No SMS or WhatsApp fallback: the
 * link is a bearer credential, and redeeming it is what proves the MAILBOX.
 *
 * <p><b>Never log the link, the token or the body.</b> The delivery reference is
 * {@code STAFF-INVITE-<inviteId>}, which carries no token, and a failure is
 * logged as that reference and the upstream HTTP status only — never the
 * exception message, which may quote the upstream's reply.
 *
 * <p>The outcome is written onto the invite row ({@code delivery_status} SENT or
 * FAILED, {@code delivered_at}) and counted on
 * {@code user.staff.invite.delivery{outcome}}. A failure is never retried
 * through another transport; the console shows FAILED as "We couldn't send the
 * invite. Check the address and resend."
 */
@Slf4j
@Component
public class StaffInviteMailer {

    public static final String SUBJECT = "Your InnBucks Foundry staff account";
    public static final String METRIC = "user.staff.invite.delivery";
    static final String BUTTON_LABEL = "Set your password";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailNotificationClient email;
    private final StaffAccountProperties properties;
    private final StaffInviteRepository invites;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactions;
    private MeterRegistry meters;

    public StaffInviteMailer(EmailNotificationClient email, StaffAccountProperties properties,
                             StaffInviteRepository invites, MarketTimeZone marketTimeZone,
                             PlatformTransactionManager transactionManager) {
        this.email = email;
        this.properties = properties;
        this.invites = invites;
        this.marketTimeZone = marketTimeZone;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meters) {
        this.meters = meters;
        if (meters != null) {
            for (String outcome : new String[]{"sent", "failed"}) {
                Counter.builder(METRIC).description("Staff invite emails by outcome")
                        .tag("outcome", outcome).register(meters);
            }
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInviteRequested(StaffInviteRequested event) {
        deliver(event);
    }

    /** The delivery itself, synchronous — {@link #onInviteRequested} is its async, after-commit entry. */
    public StaffInvite.Delivery deliver(StaffInviteRequested event) {
        String reference = "STAFF-INVITE-" + event.inviteId();
        StaffInvite.Delivery outcome;
        try {
            String link = link(event.rawToken());
            email.sendEmail(event.to(), SUBJECT, body(event, link), reference,
                    new BrandedEmailRenderer.CallToAction(BUTTON_LABEL, link));
            outcome = StaffInvite.Delivery.SENT;
            log.info("Staff invite delivered ref={}", reference);
        } catch (NotificationDeliveryException ex) {
            outcome = StaffInvite.Delivery.FAILED;
            log.warn("Staff invite delivery failed ref={} status={}", reference, upstreamStatus(ex));
        } catch (RuntimeException ex) {
            outcome = StaffInvite.Delivery.FAILED;
            log.warn("Staff invite delivery failed ref={} error={}", reference, ex.getClass().getSimpleName());
        }
        record(event.inviteId(), outcome);
        if (meters != null) {
            meters.counter(METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
        }
        return outcome;
    }

    /** {@code <console><path>#token=STI-…} — the token in the FRAGMENT, never sent to a server by a browser. */
    String link(String rawToken) {
        return properties.consoleBaseUrl() + properties.getInvitePath() + "#token=" + rawToken;
    }

    /** Plain-text copy; the branded HTML renders the same text plus the button. */
    String body(StaffInviteRequested event, String link) {
        String name = event.firstName() == null || event.firstName().isBlank() ? "there" : event.firstName();
        String roles = event.roles() == null || event.roles().isEmpty() ? "staff"
                : String.join(", ", event.roles());
        String inviter = event.invitedBy() == null || event.invitedBy().isBlank()
                ? "An InnBucks administrator" : event.invitedBy();
        return "Hi " + name + ",\n\n"
                + inviter + " has created an InnBucks Foundry staff account for you (" + roles + ").\n\n"
                + "Set your password here:\n" + link + "\n\n"
                + "The link works once and expires at " + expiry(event.expiresAt()) + ". "
                + "You'll set up two-step verification when you first sign in.\n\n"
                + "If you weren't expecting this, ignore it.";
    }

    /** "10.15 on 2 Oct", in the market's local time. */
    String expiry(LocalDateTime utc) {
        if (utc == null) return "the time shown in your console";
        OffsetDateTime local = marketTimeZone.atMarketFromUtc(utc);
        return TIME.format(local) + " on " + DAY.format(local);
    }

    private void record(Long inviteId, StaffInvite.Delivery outcome) {
        if (inviteId == null) return;
        try {
            transactions.executeWithoutResult(status -> invites.markDelivery(inviteId, outcome.name(),
                    outcome == StaffInvite.Delivery.SENT ? LocalDateTime.now(ZoneOffset.UTC) : null));
        } catch (RuntimeException ex) {
            log.warn("Could not record staff invite delivery inviteId={} outcome={}: {}",
                    inviteId, outcome, ex.getClass().getSimpleName());
        }
    }

    /** The upstream HTTP status when the failure carries one; {@code none} for a transport failure. */
    static String upstreamStatus(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException r) {
                return String.valueOf(r.getStatusCode().value());
            }
        }
        return "none";
    }
}
