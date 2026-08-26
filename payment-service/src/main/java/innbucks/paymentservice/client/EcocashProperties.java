package innbucks.paymentservice.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for <b>EcoCash Instant Payment (EIP)</b> — the third collection
 * rail. Spec distilled at {@code docs/api/ecocash-eip.md}.
 *
 * <p>Auth model: HTTP Basic ({@link #apiUsername}/{@link #apiPassword}) on
 * every call, plus the merchant identity in the request BODY
 * ({@link #merchantCode}/{@link #merchantNumber}/{@link #merchantPin}).
 * There is no login round-trip and nothing to cache.
 *
 * <p>{@link #apiPassword} and {@link #merchantPin} are secrets: env-only,
 * never committed. Blank config fails SAFE — the rail refuses charges with a
 * clean 503 and the other rails are unaffected.
 */
@Data
@ConfigurationProperties(prefix = "ecocash")
public class EcocashProperties {

    /** Environment host + gateway root, e.g.
     *  {@code https://payonline.ecocash.co.zw/ecocashGateway-preprod}. */
    private String baseUrl;

    /** HTTP Basic username issued by EcoCash. */
    private String apiUsername;

    /** HTTP Basic password issued by EcoCash. SECRET. */
    private String apiPassword;

    /** Merchant code as provided by EcoCash (body field {@code merchantCode}). */
    private String merchantCode;

    /** Merchant msisdn as provided by EcoCash (body field {@code merchantNumber}). */
    private String merchantNumber;

    /** Merchant PIN as provided by EcoCash (body field {@code merchantPin}). SECRET. */
    private String merchantPin;

    /**
     * OUR public edge URL EcoCash POSTs the final status to (request field
     * {@code notifyUrl} — mandatory upstream). Must be the ABSOLUTE public
     * URL including the edge prefix
     * ({@code https://…/foundry/payments/ecocash/notify}) — the edge strips
     * the prefix before proxying, so a bare-origin value never reaches
     * payment-service (the QR-media lesson). The webhook is a trigger only;
     * losing a notify loses nothing because the poller stays authoritative.
     */
    private String notifyUrl;

    /** {@code merchantName} sent on every charge (upstream-mandatory field). */
    private String merchantName = "TICKETIZE";

    /** {@code superMerchantName} sent on every charge (upstream-mandatory field). */
    private String superMerchantName = "INNBUCKS";

    /** {@code terminalID} sent on every charge (upstream-mandatory field). */
    private String terminalId = "TICKETIZE-WEB";

    /** {@code location} sent on every charge (upstream-mandatory field). */
    private String location = "ONLINE";

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    /**
     * Local deadline on the customer's PIN prompt. The PDF does not document
     * the upstream prompt lifetime; a still-pending Query past this deadline
     * (+ the resolver's grace) is expired LOCALLY — safe because upstream
     * positively reports unapproved at the moment of the read. Tighten once
     * the EcoCash POC answers the open question in docs/api/ecocash-eip.md.
     */
    private Duration chargeTtl = Duration.ofMinutes(5);
}
