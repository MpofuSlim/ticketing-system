package innbucks.paymentservice.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for <b>ZimSwitch Online (COPYandPAY)</b> — the card rail. Spec
 * distilled at {@code docs/api/zimswitch-copyandpay.md}.
 *
 * <p>ZimSwitch Online is a white-label of ACI's OPPWA platform, so the wire
 * contract is the standard COPYandPAY one; only the host, the entity and the
 * private-label brand are ZimSwitch-specific.
 *
 * <p>Auth model: {@code Authorization: Bearer <accessToken>} on every call
 * plus {@code entityId} as a request PARAMETER (not a header). Unlike the
 * InnBucks Merchant API there is no login round-trip — the token is
 * long-lived and issued out of band by ZimSwitch, so there is nothing to
 * cache or refresh.
 *
 * <p>{@link #accessToken} is a secret: env-only, never committed, and
 * enforced by {@code ProductionSecretsGuard} under deployment profiles. The
 * client refuses calls when base-url, entity-id or access-token is blank.
 */
@Data
@ConfigurationProperties(prefix = "zimswitch")
public class ZimswitchProperties {

    /** Environment host, e.g. {@code https://eu-test.oppwa.com} (UAT). */
    private String baseUrl;

    /** Channel identifier issued by ZimSwitch; sent as a request parameter. */
    private String entityId;

    /** Bearer token issued by ZimSwitch. SECRET. */
    private String accessToken;

    /**
     * The {@code data-brands} value the FE puts on the widget form. TICKETIZE
     * is set up in "Private label" payment mode, whose brand code is NOT the
     * doc's stock {@code VISA MASTER AMEX} — it lives here so correcting it is
     * a config change, not a redeploy of code.
     */
    private String brands = "VISA MASTER";

    /**
     * {@code testMode} sent on prepare-checkout. {@code EXTERNAL} routes UAT
     * traffic to the external test system (ZimSwitch set TICKETIZE's test
     * entity up this way). Blank in production — the parameter is then omitted
     * entirely rather than sent empty.
     */
    private String testMode;

    /**
     * The page on our FE the shopper is redirected to after paying. Sent as
     * {@code shopperResultUrl} on prepare-checkout; the widget form posts to
     * it. Must be an absolute URL the browser can reach.
     */
    private String shopperResultUrl;

    /**
     * Ask the gateway for an SRI digest ({@code integrity=true}) so the widget
     * script tag can be pinned with {@code integrity} + {@code crossorigin}.
     * On by default and there is no good reason to turn it off: the script
     * being pinned is the one that handles card entry.
     */
    private boolean requestIntegrity = true;

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    /**
     * How long a prepared checkout stays usable. The gateway's own ceiling is
     * 30 minutes ("expires when a payment has been finalized successfully by
     * user, but not later than 30 minutes"); we keep our local deadline a
     * shade under so we never advertise a checkout the gateway has already
     * dropped.
     */
    private Duration checkoutTtl = Duration.ofMinutes(28);

    /**
     * Minimum gap between status polls for the SAME checkout. The gateway
     * throttles get-payment-status to <b>two calls per minute per checkout</b>;
     * 30s keeps the reconciler inside that budget while leaving headroom for a
     * customer-triggered instant check on the same row.
     */
    private Duration statusPollMinInterval = Duration.ofSeconds(30);

    /**
     * Minimum gap before a CUSTOMER-triggered instant check ("I've paid" /
     * landing back on the result page) re-reads the same checkout's status.
     * Shorter than the poller's gap so the shopper's return usually resolves
     * immediately; the brief worst case of three reads in a minute is
     * absorbed by the gateway refusing the extra call, which the resolver
     * treats as a transient error and leaves for the next poll.
     */
    private Duration instantCheckMinGap = Duration.ofSeconds(15);
}
