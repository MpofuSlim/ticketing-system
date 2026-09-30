package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.event.SupportMfaResetNotice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The security notice to an account's business OWNERs after customer support
 * reset its second factor ({@link SupportMfaResetNotice}).
 *
 * <p>AFTER_COMMIT, so a reset that rolled back (its audit seal failed, say) never
 * announces itself; {@code @Async}, so a slow email gateway never holds the
 * agent's request. Best-effort per recipient: one bounced owner does not stop the
 * others, and a delivery failure never undoes the (already committed) reset.
 * Logs carry the user id and a count — never an address.
 */
@Component
@Slf4j
public class SupportNotificationListener {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm 'on' d MMM yyyy", Locale.ENGLISH);

    private final EmailNotificationClient email;
    private final MarketTimeZone marketTimeZone;

    public SupportNotificationListener(EmailNotificationClient email, MarketTimeZone marketTimeZone) {
        this.email = email;
        this.marketTimeZone = marketTimeZone;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSupportMfaReset(SupportMfaResetNotice notice) {
        if (notice.owners().isEmpty()) return;
        String subject = "InnBucks Foundry security notice";
        String ref = "SUPPORT-MFA-RESET-" + notice.userId();
        int sent = 0;
        for (SupportMfaResetNotice.OwnerNotice owner : notice.owners()) {
            try {
                email.sendEmail(owner.email(), subject, message(notice, owner), ref);
                sent++;
            } catch (RuntimeException ex) {
                log.warn("Support MFA-reset owner notice failed userId={} reason={}", notice.userId(),
                        ex.getClass().getSimpleName());
            }
        }
        log.info("Support MFA-reset owner notice sent userId={} owners={}/{}", notice.userId(), sent,
                notice.owners().size());
    }

    /** The notice to ONE owner, naming only the businesses that owner owns. */
    String message(SupportMfaResetNotice n, SupportMfaResetNotice.OwnerNotice owner) {
        String who = n.accountName() == null ? "a member of your business" : n.accountName();
        String account = n.accountEmail() == null ? "" : " (" + n.accountEmail() + ")";
        String businesses = owner.organizationNames().isEmpty() ? "a business"
                : String.join(", ", owner.organizationNames());
        String when = n.at() == null ? "just now"
                : WHEN.format(marketTimeZone.atMarketFromUtc(n.at()).atZoneSameInstant(marketTimeZone.zone()));
        return "Hello,\n\n"
                + "You are an owner of " + businesses + " on InnBucks Foundry. At " + when + ", InnBucks customer "
                + "support reset two-factor sign-in for " + who + account + ", a member of "
                + (owner.organizationNames().size() > 1 ? "those businesses" : "that business")
                + ". They will set it up again at their next sign-in, and every session they had open has ended.\n\n"
                + "If you didn't expect this, contact InnBucks support now, and consider removing their access "
                + "from your business until you have spoken to them.\n\n"
                + "— The InnBucks Foundry Team";
    }
}
