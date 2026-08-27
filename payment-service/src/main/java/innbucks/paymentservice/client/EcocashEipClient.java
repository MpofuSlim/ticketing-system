package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Client for <b>EcoCash Instant Payment (EIP)</b> — the wallet rail behind
 * {@code paymentRail=ECOCASH} on {@code POST /payments}. Spec pinned at
 * {@code docs/api/ecocash-eip.md} (distilled from the EIP V3 PDF in the same
 * directory).
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code POST /payment/v1/transactions/amount} — the charge: pushes a
 *       PIN prompt to the customer's phone. <b>Never retried</b>:
 *       {@code clientCorrelator} is the upstream idempotency key (a duplicate
 *       is rejected), and a FRESH correlator on a blind retry could debit the
 *       customer twice. Circuit breaker only.</li>
 *   <li>{@code GET /payment/v1/{endUserId}/transactions/amount/{clientCorrelator}}
 *       — the Query. Read-only and repeatable, so retried on transients; the
 *       only resolver of an ambiguous charge.</li>
 * </ul>
 *
 * <p>Wire conventions (per the doc): JSON bodies, HTTP Basic auth, and
 * <b>amounts as decimal JSON NUMBERS in MAJOR units</b> ({@code 3.00}) — the
 * third convention (InnBucks: integer cents; ZimSwitch: major-unit strings).
 * This client takes {@code long amountCents} (the service layer's universal
 * unit) and owns the one cents→major rendering for this rail.
 *
 * <p><b>Raw bodies are never logged.</b> EIP responses echo the merchant PIN
 * (the PDF's own samples show it), so this client maps a trimmed
 * {@link EcocashChargeStatus} and error paths log status codes plus at most
 * an extracted message field — never the envelope.
 *
 * <p><b>Identity echoes are not trusted.</b> The PDF's samples return
 * {@code endUserId} without its country code on one call and with the
 * merchant/customer fields SWAPPED on another; the only load-bearing response
 * fields are the status, the references and the amounts.
 */
@Slf4j
@Component
public class EcocashEipClient {

    private static final String RESILIENCE_INSTANCE_NAME = "ecocash";
    private static final String CHARGE_PATH = "/payment/v1/transactions/amount";

    /**
     * Identify ourselves honestly on every EIP call. The JDK HttpClient's
     * default is {@code Java-http-client/<ver>}, which EcoCash's Cloudflare
     * layer refuses with a bodyless 403 — their edge runs a User-Agent
     * ALLOW-list (verified 2026-08-27 from the ZW cell: {@code curl/8.x} → 200,
     * {@code Java-http-client/21} → 403, and this string → 403 as well, so it
     * only starts working once EcoCash allow-lists it).
     *
     * <p>It is therefore <b>not</b> a fix on its own — it is the stable
     * identity we ask them to allow, and it stops us being an anonymous
     * generic-runtime caller against a payment gateway. Never spoof a browser
     * or another tool's UA here: the whole point is that EcoCash can tell who
     * is calling.
     */
    static final String USER_AGENT = "Ticketize-Payments/1.0";

    /**
     * Legal shapes of the two values we splice into the Query URL. Both are
     * OUR values (we mint the correlator; the msisdn comes from the order
     * snapshot) — this is defence in depth against ever building a request
     * path from a corrupted stored value, mirroring the ZimSwitch
     * CHECKOUT_ID_SHAPE guard.
     */
    private static final Pattern CORRELATOR_SHAPE = Pattern.compile("^[A-Za-z0-9]{8,64}$");
    private static final Pattern MSISDN_SHAPE = Pattern.compile("^\\+?[0-9]{6,15}$");

    private final EcocashProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public EcocashEipClient(EcocashProperties properties,
                            ObjectMapper objectMapper,
                            RetryRegistry retryRegistry,
                            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl() == null ? "http://localhost" : trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        warnOnHalfProvisionedRail();
    }

    /**
     * Surface a half-provisioned rail at BOOT rather than at the first
     * customer attempt — the ZimSwitch shopper-result-URL lesson, and after
     * the QR-media incident the notify URL is exactly the kind of value that
     * silently ships wrong: it must be the ABSOLUTE public edge URL
     * (prefix included), because EcoCash resolves it from the public
     * internet, not from inside the cluster.
     */
    private void warnOnHalfProvisionedRail() {
        if (isConfigured() && !isUsableNotifyUrl(properties.getNotifyUrl())) {
            log.error("EcoCash rail is HALF-PROVISIONED: credentials are set but ECOCASH_NOTIFY_URL "
                    + "is blank or not an absolute http(s) URL. Charges will be REFUSED (503) until it "
                    + "is set to the public edge webhook (https://…/foundry/payments/ecocash/notify) — "
                    + "see docs/api/ecocash-eip.md.");
        } else if (isConfigured()) {
            log.info("EcoCash rail configured; notify URL host={}",
                    java.net.URI.create(properties.getNotifyUrl().trim()).getHost());
        }
    }

    /**
     * True when we can TALK to the gateway — the predicate the poller runs
     * on: an already-open charge must stay resolvable even if the cell's
     * notify URL is missing, otherwise a config slip strands rows whose
     * prompt a customer may have approved.
     */
    public boolean isConfigured() {
        return notBlank(properties.getBaseUrl())
                && notBlank(properties.getApiUsername())
                && notBlank(properties.getApiPassword())
                && notBlank(properties.getMerchantCode())
                && notBlank(properties.getMerchantNumber())
                && notBlank(properties.getMerchantPin());
    }

    /**
     * True when we can START a charge: gateway reachable AND a usable
     * absolute notify URL ({@code notifyUrl} is upstream-mandatory). Split
     * from {@link #isConfigured()} for the same reason as the ZimSwitch
     * rail: refusing at the gate is a clean 503, while a half-provisioned
     * charge claims the order's only payment slot.
     */
    public boolean canStartCharge() {
        return isConfigured() && isUsableNotifyUrl(properties.getNotifyUrl());
    }

    static boolean isUsableNotifyUrl(String url) {
        if (!notBlank(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Issue the charge (pushes the PIN prompt). Single attempt — circuit
     * breaker only, NEVER retried (see class javadoc). A 2xx maps to the
     * trimmed status (expected {@code PENDING}); an active 4xx refusal
     * throws {@link EcocashApiException}; anything ambiguous throws
     * {@link EcocashApiTransientException} and the CALLER leaves the row
     * open for the Query poller — a prompt may be live on the customer's
     * phone, so ambiguous is never treated as failed.
     *
     * @param clientCorrelator our idempotency key, already persisted on the
     *                         ledger row
     * @param referenceCode    our TKZ-… payment reference (the doc's
     *                         "your reference code")
     */
    public EcocashChargeStatus charge(String clientCorrelator,
                                      String endUserMsisdn,
                                      long amountCents,
                                      String currency,
                                      String referenceCode) {
        requireConfigured();
        Supplier<EcocashChargeStatus> call =
                () -> doCharge(clientCorrelator, endUserMsisdn, amountCents, currency, referenceCode);
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new EcocashApiTransientException(
                    "EcoCash gateway is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private EcocashChargeStatus doCharge(String clientCorrelator, String endUserMsisdn,
                                         long amountCents, String currency, String referenceCode) {
        Map<String, Object> chargingInformation = new LinkedHashMap<>();
        // The one cents -> major-units rendering for this rail; a JSON
        // NUMBER (3.00), not a string — Jackson serializes BigDecimal as one.
        chargingInformation.put("amount", BigDecimal.valueOf(amountCents, 2));
        chargingInformation.put("currency", currency);
        chargingInformation.put("description", "Ticketize online payment");

        Map<String, Object> chargeMetaData = new LinkedHashMap<>();
        chargeMetaData.put("channel", "WEB");
        chargeMetaData.put("purchaseCategoryCode", "Online Payment");
        chargeMetaData.put("onBeHalfOf", properties.getMerchantName());

        Map<String, Object> paymentAmount = new LinkedHashMap<>();
        paymentAmount.put("charginginformation", chargingInformation);
        paymentAmount.put("chargeMetaData", chargeMetaData);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientCorrelator", clientCorrelator);
        body.put("notifyUrl", properties.getNotifyUrl().trim());
        body.put("referenceCode", referenceCode);
        body.put("tranType", "MER");
        body.put("endUserId", normalizeMsisdn(endUserMsisdn));
        body.put("remarks", "Ticketize online payment");
        body.put("transactionOperationStatus", "Charged");
        body.put("paymentAmount", paymentAmount);
        body.put("merchantCode", properties.getMerchantCode());
        body.put("merchantPin", properties.getMerchantPin());
        body.put("merchantNumber", properties.getMerchantNumber());
        body.put("currencyCode", currency);
        body.put("countryCode", "ZW");
        body.put("terminalID", properties.getTerminalId());
        body.put("location", properties.getLocation());
        body.put("superMerchantName", properties.getSuperMerchantName());
        body.put("merchantName", properties.getMerchantName());

        try {
            String raw = restClient.post()
                    .uri(CHARGE_PATH)
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            EcocashChargeStatus status = classify(raw);
            // Status + references only — the raw envelope echoes the PIN.
            log.info("ecocash charge clientCorrelator={} outcome={} rawStatus={} ecocashReference={}",
                    clientCorrelator, status.outcome(), status.rawStatus(), status.ecocashReference());
            return status;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 500) {
                // Ambiguous: the charge MAY exist and a prompt may be live.
                throw new EcocashApiTransientException(
                        "EcoCash returned HTTP " + status + " on charge", status, e);
            }
            // 4xx: the gateway actively refused — no prompt was pushed.
            String reason = extractErrorMessage(e.getResponseBodyAsString());
            log.warn("ecocash refused charge clientCorrelator={} status={} reason={}",
                    clientCorrelator, status, reason);
            throw new EcocashApiException(
                    "EcoCash refused the charge: HTTP " + status
                            + (reason != null ? " — " + reason : ""), status, e);
        } catch (EcocashApiException | EcocashApiTransientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ecocash charge errored clientCorrelator={} cause={}", clientCorrelator, e.toString());
            throw new EcocashApiTransientException(
                    "Unable to reach the EcoCash gateway: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Query the transaction keyed by (customer msisdn, our correlator) — the
     * only retried call, and the only truth source for an open charge. A 404
     * (or an envelope with no readable status) comes back as
     * {@link EcocashChargeStatus.Outcome#NOT_FOUND} / {@code UNKNOWN} rather
     * than throwing: both are answers the resolver has rules for.
     */
    public EcocashChargeStatus query(String endUserMsisdn, String clientCorrelator) {
        requireConfigured();
        String msisdn = normalizeMsisdn(endUserMsisdn);
        if (!CORRELATOR_SHAPE.matcher(clientCorrelator == null ? "" : clientCorrelator).matches()
                || !MSISDN_SHAPE.matcher(msisdn).matches()) {
            // Never splice an out-of-shape value into a request path.
            log.error("ecocash query refused: stored correlator/msisdn out of shape correlator={}", clientCorrelator);
            return new EcocashChargeStatus(EcocashChargeStatus.Outcome.UNKNOWN,
                    "unqueryable", null, null, null, null);
        }
        Supplier<EcocashChargeStatus> call = () -> doQuery(msisdn, clientCorrelator);
        Supplier<EcocashChargeStatus> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, Retry.decorateSupplier(retry, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new EcocashApiTransientException(
                    "EcoCash gateway is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private EcocashChargeStatus doQuery(String msisdn, String clientCorrelator) {
        try {
            String raw = restClient.get()
                    .uri("/payment/v1/{endUserId}/transactions/amount/{clientCorrelator}",
                            msisdn, clientCorrelator)
                    .header("Authorization", basicAuth())
                    .retrieve()
                    .body(String.class);
            return classify(raw);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                // The positive "no such transaction" answer — normal for a
                // beat after issuing; a crash-before-call signature later.
                return new EcocashChargeStatus(EcocashChargeStatus.Outcome.NOT_FOUND,
                        "HTTP 404", null, null, null, null);
            }
            if (status >= 500) {
                throw new EcocashApiTransientException(
                        "EcoCash returned HTTP " + status + " on query", status, e);
            }
            String reason = extractErrorMessage(e.getResponseBodyAsString());
            log.warn("ecocash query rejected clientCorrelator={} status={} reason={}",
                    clientCorrelator, status, reason);
            return new EcocashChargeStatus(EcocashChargeStatus.Outcome.UNKNOWN,
                    "HTTP " + status + (reason != null ? " — " + reason : ""), null, null, null, null);
        } catch (EcocashApiTransientException e) {
            throw e;
        } catch (Exception e) {
            throw new EcocashApiTransientException(
                    "Unable to reach the EcoCash gateway: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Map a raw envelope onto the trimmed status. Anything that is not
     * COMPLETED or FAILED is PENDING (the still-pending set is open-ended per
     * the doc); an unreadable body is UNKNOWN, which never expires a row.
     */
    private EcocashChargeStatus classify(String raw) {
        // A 2xx whose body is not an EIP envelope did not come FROM EIP: it is
        // infrastructure answering on its behalf (EcoCash sits behind
        // Cloudflare and an F5 BIG-IP ASM, and the ASM serves its "Request
        // Rejected / support ID" page with HTTP **200** and text/html). Such a
        // body must never be classified as a status — mapping it to UNKNOWN
        // read as "the customer has not decided yet", and UNKNOWN never closes
        // a row, so a sustained block would pin every charge in TOKEN_ISSUED
        // forever holding the order's only payment slot across all rails.
        // Treating it as transient routes it through the same path as a
        // timeout: retried on the Query, counted as an error, alertable.
        if (raw == null || raw.isBlank()) {
            throw new EcocashApiTransientException(
                    "EcoCash returned an empty body where a JSON envelope was expected", 502);
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(raw);
        } catch (Exception e) {
            // Safe to log a prefix: a non-JSON body is by definition not our
            // envelope, so it cannot carry the merchant PIN — and an F5 block
            // page contains the support ID an operator needs to give EcoCash.
            log.error("[ecocash] NON-JSON body on a 2xx — infrastructure answered, not EIP. "
                    + "Body starts: {}", truncate(raw.strip(), 200));
            throw new EcocashApiTransientException(
                    "EcoCash returned a non-JSON body on a 2xx (blocked at the edge?)", 502, e);
        }
        if (!parsed.isObject()) {
            log.error("[ecocash] 2xx body is JSON but not an object — not an EIP envelope: {}",
                    truncate(raw.strip(), 200));
            throw new EcocashApiTransientException(
                    "EcoCash returned a JSON non-object where an envelope was expected", 502);
        }
        try {
            JsonNode root = parsed;
            String rawStatus = text(root, "transactionOperationStatus");
            String reference = text(root, "ecocashReference");
            if (reference == null) reference = text(root, "serverReferenceCode");
            JsonNode paymentAmount = root.path("paymentAmount");
            BigDecimal totalCharged = decimal(paymentAmount.path("totalAmountCharged"));
            JsonNode charging = paymentAmount.path("charginginformation");
            BigDecimal amountEcho = decimal(charging.path("amount"));
            String currencyEcho = charging.path("currency").isTextual()
                    ? charging.path("currency").asText() : null;

            EcocashChargeStatus.Outcome outcome;
            if (rawStatus == null || rawStatus.isBlank()) {
                // VERIFIED 2026-08-27 against the preprod gateway: a Query for
                // a correlator EcoCash has never seen answers HTTP 200 with a
                // full envelope whose every field is null — NOT the 404 the
                // spec doc assumed. The discriminator is that the envelope
                // echoes NOTHING back: a transaction EcoCash knows returns our
                // clientCorrelator (and usually a reference) even when the
                // status is unreadable. So "no status AND no echo" is the
                // positive no-such-transaction answer -> NOT_FOUND, which the
                // resolver still only acts on AFTER the prompt deadline +
                // grace. A null status WITH an echo stays UNKNOWN: EcoCash
                // knows the transaction, we just cannot read its state, and
                // guessing there could free a slot the customer has paid for.
                boolean echoesNothing = text(root, "clientCorrelator") == null && reference == null;
                outcome = echoesNothing
                        ? EcocashChargeStatus.Outcome.NOT_FOUND
                        : EcocashChargeStatus.Outcome.UNKNOWN;
            } else {
                String s = rawStatus.trim().toUpperCase(Locale.ROOT);
                outcome = switch (s) {
                    case "COMPLETED" -> EcocashChargeStatus.Outcome.COMPLETED;
                    case "FAILED" -> EcocashChargeStatus.Outcome.FAILED;
                    default -> EcocashChargeStatus.Outcome.PENDING;
                };
            }
            return new EcocashChargeStatus(outcome, rawStatus, reference,
                    amountEcho, totalCharged, currencyEcho);
        } catch (Exception e) {
            log.warn("ecocash response unparseable: {}", e.toString());
            return new EcocashChargeStatus(EcocashChargeStatus.Outcome.UNKNOWN,
                    "unparseable body", null, null, null, null);
        }
    }

    /** Best-effort human reason from an ERROR envelope — never the whole body. */
    private String extractErrorMessage(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(raw);
            String msg = text(root, "message");
            if (msg == null) msg = text(root, "exception");
            if (msg == null) msg = text(root, "error");
            return msg == null ? null : truncate(msg, 200);
        } catch (Exception e) {
            return null;
        }
    }

    private String basicAuth() {
        String pair = properties.getApiUsername() + ":" + properties.getApiPassword();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * EIP wants the msisdn country-coded WITHOUT a plus
     * ({@code 263777222093}); the fleet stores E.164 with one. Strip it here
     * — the single normalisation point for this rail.
     */
    static String normalizeMsisdn(String msisdn) {
        if (msisdn == null) return "";
        String trimmed = msisdn.trim();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new EcocashApiException("EcoCash rail is not configured on this deployment", 503);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.path(field);
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static BigDecimal decimal(JsonNode n) {
        return n.isNumber() ? n.decimalValue() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Registers {@link EcocashProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(EcocashProperties.class)
    static class PropertiesRegistrar {
    }
}
