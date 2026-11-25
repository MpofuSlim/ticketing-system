package innbucks.paymentservice.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Paynow settings ({@code docs/api/paynow.md}). Blank credentials fail SAFE:
 * a Paynow rail with no integration for the order's currency refuses with a
 * clean 503 and the other rails are unaffected.
 *
 * <p>Paynow's initiate request has no currency field: each integration is in
 * one currency, so the merchant holds one Integration ID + Key per currency
 * and the order's currency picks which one we sign with. Both keys are
 * SECRETS (whoever holds one can sign messages that pass as Paynow's): set
 * them only in a host's gitignored {@code cell.<iso>.local.env}.
 */
@Data
@ConfigurationProperties(prefix = "paynow")
public class PaynowProperties {

    /** Paynow's host. Test mode runs on the live host too. */
    private String baseUrl = "https://www.paynow.co.zw";

    /**
     * Hosts the URLs Paynow hands back (browserurl, pollurl, remoteotpurl)
     * may name, comma-separated. Blank = the base URL's host only. We POST
     * to the poll and OTP URLs server-side, so a URL naming any other host
     * is refused, never called.
     */
    private String allowedHosts;

    private String usdIntegrationId;
    /** SECRET. */
    private String usdIntegrationKey;
    private String zwgIntegrationId;
    /** SECRET. */
    private String zwgIntegrationKey;

    /**
     * ABSOLUTE public URL of {@code POST /payments/paynow/result}, prefix
     * included ({@code https://…/foundry/payments/paynow/result}): Paynow
     * resolves it from the public internet.
     */
    private String resultUrl;

    /**
     * The website page a card customer comes back to after Paynow's page.
     * We append {@code transactionId=<our payment id>} so the page can poll
     * {@code GET /payments/{transactionId}}.
     */
    private String returnUrl;

    /**
     * {@code authemail} for express checkout (OneMoney, O'mari), which Paynow
     * requires there. In TEST MODE it must be a login email of the merchant
     * account, or Paynow refuses the payment.
     */
    private String authEmail;

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;

    /** How long a card customer has on Paynow's page before we stop waiting. */
    private Duration cardTtl = Duration.ofMinutes(30);
    /** How long a OneMoney prompt / O'mari code stays open on our side. */
    private Duration mobileTtl = Duration.ofMinutes(10);

    /** Least time between two status checks of one payment, except on a Paynow status update. */
    private Duration checkMinGap = Duration.ofSeconds(15);
    /** How often the sweep re-checks an open payment whose status update may have been lost. */
    private Duration recheckEvery = Duration.ofMinutes(2);

    /**
     * After a OneMoney or O'mari attempt is declined or cancelled, how long a
     * new one for the same order on the same rail is refused with that decline
     * (EcoCash's decline hold). 0 = off.
     */
    private Duration declineHold = Duration.ofMinutes(2);

    /** Per-number cap on OneMoney prompts / O'mari codes, like EcoCash's. 0 = off. */
    private int maxPromptsPerNumber = 5;
    private Duration promptsPerNumberWindow = Duration.ofMinutes(15);

    /**
     * Whether OneMoney may be started with Paynow's published test numbers
     * (0771111111 to 0774444444). False everywhere but a staging host: while an
     * integration is in test mode Paynow reports those numbers PAID with no
     * money moved, so on a cell that sells real tickets they would be free
     * tickets. On a live integration they are Econet numbers, never OneMoney
     * wallets, so refusing them costs nothing.
     */
    private boolean testNumbersAllowed = false;

    /**
     * How long after we close a Paynow payment unpaid we keep looking for a
     * late payment (a card page can outlive our deadline, and Paynow's update
     * about it can be lost). Checked every {@link #closedRecheckFirstHourEvery}
     * in the first hour, then every {@link #closedRecheckLaterEvery}.
     */
    private Duration closedRecheckFor = Duration.ofHours(24);
    private Duration closedRecheckFirstHourEvery = Duration.ofMinutes(15);
    private Duration closedRecheckLaterEvery = Duration.ofHours(3);

    /**
     * How long one sweep run keeps starting checks; the rest wait for the next
     * run. The scheduler has one thread for every rail's poller, so a Paynow
     * that hangs (each check up to three reads at the read timeout) must not
     * hold it for the whole page.
     */
    private Duration sweepRunBudget = Duration.ofSeconds(25);

    /** One integration's credentials. */
    public record Integration(String id, String key) {}

    /** The integration for {@code currency}, when both its id and key are set. */
    public Optional<Integration> integrationFor(String currency) {
        if (currency == null) return Optional.empty();
        String c = currency.strip().toUpperCase(Locale.ROOT);
        if ("USD".equals(c) && notBlank(usdIntegrationId) && notBlank(usdIntegrationKey)) {
            return Optional.of(new Integration(usdIntegrationId.strip(), usdIntegrationKey.strip()));
        }
        if (("ZWG".equals(c) || "ZIG".equals(c)) && notBlank(zwgIntegrationId) && notBlank(zwgIntegrationKey)) {
            return Optional.of(new Integration(zwgIntegrationId.strip(), zwgIntegrationKey.strip()));
        }
        return Optional.empty();
    }

    /** Whether any currency has an integration. */
    public boolean anyIntegration() {
        return integrationFor("USD").isPresent() || integrationFor("ZWG").isPresent();
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
