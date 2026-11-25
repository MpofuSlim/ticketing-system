package innbucks.paymentservice.client;

import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.config.PooledHttpClient;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Client for <b>Paynow</b> ({@code docs/api/paynow.md}), the gateway behind
 * the {@code PAYNOW_CARD}, {@code ONEMONEY} and {@code OMARI} rails.
 *
 * <p>Calls, all {@code application/x-www-form-urlencoded} POSTs answered in
 * the same {@code key=value&…} form:
 * <ul>
 *   <li><b>Initiate</b> ({@code /interface/initiatetransaction}, web checkout:
 *       a page the customer pays on) and <b>express checkout</b>
 *       ({@code /interface/remotetransaction}, OneMoney prompt or O'mari code).
 *       <b>Never retried</b>: Paynow has no idempotency key on initiate, so a
 *       retry could start a second payment. Each request carries our own
 *       {@code merchanttrace} instead, which {@link #trace} looks a payment up
 *       by when the reply never arrived.</li>
 *   <li><b>Poll</b> (an empty POST to the {@code pollurl} Paynow gave us) and
 *       <b>trace</b> ({@code /interface/trace}): read-only, retried.</li>
 *   <li><b>O'mari code</b> (POST to the {@code remoteotpurl}): never retried,
 *       because each try spends one of the customer's five attempts.</li>
 * </ul>
 *
 * <p><b>Every message is signed</b> ({@link PaynowHash}). We sign what we
 * send, and an {@code Ok} reply or a status whose hash does not verify is
 * treated as no answer ({@link PaynowApiTransientException}), never acted on.
 * {@code status=Error} replies carry no hash in Paynow's docs; they arrive on
 * the TLS connection we opened, which is what makes them Paynow's.
 *
 * <p><b>Paynow's URLs are pinned.</b> We POST to the poll and O'mari URLs
 * server-side and send the customer's browser to the page URL, so each must
 * name an allowed Paynow host ({@link #isPaynowUrl}); any other value is
 * refused, never called.
 *
 * <p><b>Amounts are decimal strings in major units</b> ({@code "99.99"}): the
 * one cents-to-major rendering for this rail lives here.
 */
@Slf4j
@Component
public class PaynowClient {

    private static final String RESILIENCE_INSTANCE_NAME = "paynow";
    static final String INITIATE_PATH = "/interface/initiatetransaction";
    static final String EXPRESS_PATH = "/interface/remotetransaction";
    static final String TRACE_PATH = "/interface/trace";

    /** A new partner: we name ourselves honestly, as with EcoCash. */
    static final String USER_AGENT = "Ticketize-Payments/1.0";

    /** Our merchant trace: 32 lower-case hex characters (Paynow allows up to 32). */
    private static final Pattern TRACE_SHAPE = Pattern.compile("^[0-9a-f]{32}$");

    private final PaynowProperties properties;
    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public PaynowClient(PaynowProperties properties,
                        RetryRegistry retryRegistry,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        PooledHttpClient pooledHttpClient) {
        this.properties = properties;
        // The module's shared pool, this client's own timeouts (CLAUDE.md,
        // "Outbound HTTP clients are pooled").
        var rf = pooledHttpClient.requestFactory(properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(rf)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
        warnOnHalfProvisionedRail();
    }

    /**
     * A key with no result URL is the quiet failure: payments would start,
     * Paynow could never tell us one was paid, and only the sweep would find
     * out, minutes later. Said at boot, like the EcoCash notify URL.
     */
    private void warnOnHalfProvisionedRail() {
        if (!properties.anyIntegration()) {
            return;
        }
        if (!isUsableAbsoluteUrl(properties.getResultUrl())) {
            log.error("Paynow is HALF-PROVISIONED: an integration is set but PAYNOW_RESULT_URL is blank or not an "
                    + "absolute http(s) URL. Paynow payments will be REFUSED (503) until it is set to the public "
                    + "edge URL (https://…/foundry/payments/paynow/result) — see docs/api/paynow.md.");
        }
        if (!isUsableAbsoluteUrl(properties.getReturnUrl())) {
            log.error("Paynow is HALF-PROVISIONED: PAYNOW_RETURN_URL is blank or not an absolute http(s) URL. "
                    + "Card payments through Paynow will be REFUSED (503) until it names the website page a "
                    + "customer returns to.");
        }
        if (properties.getAuthEmail() == null || properties.getAuthEmail().isBlank()) {
            log.error("Paynow is HALF-PROVISIONED: PAYNOW_AUTH_EMAIL is blank. OneMoney and O'mari payments "
                    + "through Paynow will be REFUSED (503): Paynow requires authemail on express checkout.");
        }
        log.info("Paynow configured: usd={} zwg={}", properties.integrationFor("USD").isPresent(),
                properties.integrationFor("ZWG").isPresent());
    }

    /** Whether we hold an integration for {@code currency} (the poller's predicate). */
    public boolean isConfigured(String currency) {
        return properties.integrationFor(currency).isPresent();
    }

    /** Whether any currency has an integration. */
    public boolean hasAnyIntegration() {
        return properties.anyIntegration();
    }

    /** Whether a web (card) payment can start in {@code currency}. */
    public boolean canStartWeb(String currency) {
        return isConfigured(currency)
                && isUsableAbsoluteUrl(properties.getResultUrl())
                && isUsableAbsoluteUrl(properties.getReturnUrl());
    }

    /** Whether an express (OneMoney, O'mari) payment can start in {@code currency}. */
    public boolean canStartExpress(String currency) {
        return isConfigured(currency)
                && isUsableAbsoluteUrl(properties.getResultUrl())
                && properties.getAuthEmail() != null && !properties.getAuthEmail().isBlank();
    }

    // ---------------------------------------------------------------------
    // Starting a payment (never retried)
    // ---------------------------------------------------------------------

    /**
     * Start a web checkout: Paynow answers with the page the customer pays on.
     *
     * @param returnUrl where Paynow sends the customer's browser afterwards
     * @throws PaynowApiException          Paynow refused ({@code status=Error}); nothing was created
     * @throws PaynowApiTransientException no usable answer; a payment MAY exist (trace it)
     */
    public PaynowInitiation initiateWeb(String currency, String reference, long amountCents,
                                        String additionalInfo, String returnUrl, String merchantTrace) {
        PaynowProperties.Integration integration = requireIntegration(currency);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", integration.id());
        fields.put("reference", reference);
        fields.put("amount", major(amountCents));
        fields.put("additionalinfo", additionalInfo);
        fields.put("returnurl", returnUrl);
        fields.put("resulturl", properties.getResultUrl().strip());
        fields.put("merchanttrace", requireTrace(merchantTrace));
        fields.put("status", "Message");
        return initiate(INITIATE_PATH, fields, integration, reference, false);
    }

    /**
     * Start an express checkout: a OneMoney prompt ({@code method=onemoney})
     * or an O'mari code texted to the customer ({@code method=omari}).
     *
     * @throws PaynowApiException          Paynow refused, e.g. "Insufficient balance"; nothing was created
     * @throws PaynowApiTransientException no usable answer; a prompt MAY be live (trace it)
     */
    public PaynowInitiation initiateExpress(String currency, String reference, long amountCents,
                                            String additionalInfo, String merchantTrace,
                                            String method, String phone) {
        PaynowProperties.Integration integration = requireIntegration(currency);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", integration.id());
        fields.put("reference", reference);
        fields.put("amount", major(amountCents));
        fields.put("additionalinfo", additionalInfo);
        // Express has no browser, but returnurl is one of the initiate fields
        // express adds to; the website page when set, else the result URL.
        fields.put("returnurl", isUsableAbsoluteUrl(properties.getReturnUrl())
                ? properties.getReturnUrl().strip() : properties.getResultUrl().strip());
        fields.put("resulturl", properties.getResultUrl().strip());
        fields.put("authemail", properties.getAuthEmail().strip());
        fields.put("phone", phone);
        fields.put("method", method);
        fields.put("merchanttrace", requireTrace(merchantTrace));
        fields.put("status", "Message");
        return initiate(EXPRESS_PATH, fields, integration, reference, true);
    }

    private PaynowInitiation initiate(String path, Map<String, String> fields,
                                      PaynowProperties.Integration integration,
                                      String reference, boolean express) {
        fields.put("hash", PaynowHash.forOutbound(fields, integration.key()));
        Supplier<PaynowInitiation> call = () -> {
            PaynowMessage reply = post(URI.create(baseUrl() + path), fields, "initiate");
            String status = reply.status();
            if ("error".equals(status)) {
                String error = clean(reply.get("error"));
                log.warn("paynow refused {} reference={} error={}", express ? "express checkout" : "initiate",
                        reference, error);
                throw new PaynowApiException("Paynow refused the payment"
                        + (error != null ? ": " + error : ""), error);
            }
            if (!"ok".equals(status)) {
                throw new PaynowApiTransientException("Paynow initiate answered without a readable status");
            }
            if (!PaynowHash.verify(reply, integration.key())) {
                // Paynow: verify the hash BEFORE sending the customer to the page.
                throw new PaynowApiTransientException("Paynow initiate reply failed its hash check");
            }
            String pollUrl = reply.text("pollurl");
            if (pollUrl == null || !isPaynowUrl(pollUrl)) {
                throw new PaynowApiTransientException("Paynow initiate reply has no usable pollurl");
            }
            String browserUrl = reply.text("browserurl");
            String remoteOtpUrl = reply.text("remoteotpurl");
            if (browserUrl != null && !isPaynowUrl(browserUrl)) {
                throw new PaynowApiTransientException("Paynow initiate reply names a page off Paynow's hosts");
            }
            if (remoteOtpUrl != null && !isPaynowUrl(remoteOtpUrl)) {
                throw new PaynowApiTransientException("Paynow initiate reply names an OTP URL off Paynow's hosts");
            }
            log.info("paynow {} started reference={} paynowReference={}",
                    express ? "express checkout" : "web checkout", reference, reply.text("paynowreference"));
            return new PaynowInitiation(browserUrl, pollUrl, reply.text("paynowreference"),
                    reply.text("otpreference"), remoteOtpUrl, clean(reply.get("instructions")));
        };
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new PaynowApiTransientException("Paynow is temporarily unavailable (circuit open)", e);
        }
    }

    // ---------------------------------------------------------------------
    // Reading a payment (retried)
    // ---------------------------------------------------------------------

    /** Poll a payment: an empty POST to its {@code pollurl}. */
    public PaynowStatus poll(String currency, String pollUrl) {
        PaynowProperties.Integration integration = requireIntegration(currency);
        if (pollUrl == null || !isPaynowUrl(pollUrl)) {
            // A stored value we would not call: never splice it into a request.
            throw new PaynowApiTransientException("refusing to poll a URL off Paynow's hosts");
        }
        return read(() -> {
            PaynowMessage reply = post(URI.create(pollUrl.strip()), Map.of(), "poll");
            return statusOf(reply, integration, "poll");
        });
    }

    /**
     * Look a payment up by our merchant trace: how we learn whether a payment
     * exists, and its poll URL, after an initiate whose reply never arrived.
     */
    public PaynowStatus trace(String currency, String merchantTrace) {
        PaynowProperties.Integration integration = requireIntegration(currency);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", integration.id());
        fields.put("merchanttrace", requireTrace(merchantTrace));
        fields.put("status", "Message");
        fields.put("hash", PaynowHash.forOutbound(fields, integration.key()));
        return read(() -> {
            PaynowMessage reply = post(URI.create(baseUrl() + TRACE_PATH), fields, "trace");
            if ("notfound".equals(reply.status())) {
                if (reply.text("hash") != null && !PaynowHash.verify(reply, integration.key())) {
                    throw new PaynowApiTransientException("Paynow trace NotFound failed its hash check");
                }
                return new PaynowStatus(PaynowStatus.Outcome.NOT_FOUND, "NotFound", null, null, null, null);
            }
            // Paynow: "A trace error does not necessarily mean that the
            // transaction was not found" -- so an Error is no answer.
            return statusOf(reply, integration, "trace");
        });
    }

    /**
     * Send the code the customer typed to O'mari's {@code remoteotpurl}.
     * Never retried: each try spends one of the customer's five attempts.
     *
     * @throws PaynowApiException          the code was refused, e.g. "Invalid OTP"
     * @throws PaynowApiTransientException no usable answer; the status check resolves it
     */
    public PaynowStatus submitOtp(String currency, String remoteOtpUrl, String otp) {
        PaynowProperties.Integration integration = requireIntegration(currency);
        if (remoteOtpUrl == null || !isPaynowUrl(remoteOtpUrl)) {
            throw new PaynowApiTransientException("refusing to post a code to a URL off Paynow's hosts");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", integration.id());
        fields.put("otp", otp);
        fields.put("status", "Message");
        fields.put("hash", PaynowHash.forOutbound(fields, integration.key()));
        Supplier<PaynowStatus> call = () -> {
            PaynowMessage reply = post(URI.create(remoteOtpUrl.strip()), fields, "otp");
            if ("error".equals(reply.status())) {
                String error = clean(reply.get("error"));
                throw new PaynowApiException("Paynow refused the code" + (error != null ? ": " + error : ""), error);
            }
            return statusOf(reply, integration, "otp");
        };
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new PaynowApiTransientException("Paynow is temporarily unavailable (circuit open)", e);
        }
    }

    /**
     * A status message Paynow posted to our result URL, when its hash
     * verifies under the integration for {@code currency}; empty otherwise.
     * A verified update is still only a trigger: the caller confirms by
     * polling, as Paynow recommends.
     */
    public Optional<PaynowStatus> verifiedUpdate(String currency, PaynowMessage message) {
        Optional<PaynowProperties.Integration> integration = properties.integrationFor(currency);
        if (integration.isEmpty() || !PaynowHash.verify(message, integration.get().key())) {
            return Optional.empty();
        }
        return Optional.of(toStatus(message));
    }

    private PaynowStatus read(Supplier<PaynowStatus> call) {
        Supplier<PaynowStatus> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, Retry.decorateSupplier(retry, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new PaynowApiTransientException("Paynow is temporarily unavailable (circuit open)", e);
        }
    }

    private PaynowStatus statusOf(PaynowMessage reply, PaynowProperties.Integration integration, String op) {
        String status = reply.status();
        if (status == null || "error".equals(status)) {
            throw new PaynowApiTransientException("Paynow " + op + " answered "
                    + (status == null ? "without a status" : "Error: " + clean(reply.get("error"))));
        }
        if (!PaynowHash.verify(reply, integration.key())) {
            throw new PaynowApiTransientException("Paynow " + op + " reply failed its hash check");
        }
        return toStatus(reply);
    }

    private PaynowStatus toStatus(PaynowMessage m) {
        String raw = m.text("status");
        String pollUrl = m.text("pollurl");
        return new PaynowStatus(PaynowStatus.outcomeOf(raw), raw, m.text("reference"),
                m.text("paynowreference"), amountOf(m.text("amount")),
                pollUrl != null && isPaynowUrl(pollUrl) ? pollUrl : null);
    }

    private PaynowMessage post(URI uri, Map<String, String> fields, String op) {
        try {
            String raw = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formOf(fields))
                    .retrieve()
                    .body(String.class);
            PaynowMessage reply = PaynowMessage.parse(raw);
            if (reply.isEmpty() || reply.status() == null) {
                // An HTML error page, an edge block, an empty body: not Paynow's answer.
                throw new PaynowApiTransientException("Paynow " + op + " answered with no Paynow message");
            }
            return reply;
        } catch (RestClientResponseException e) {
            // Paynow answers in the body with HTTP 200; any non-2xx is the edge
            // or an outage, never a verdict on the payment.
            throw new PaynowApiTransientException("Paynow returned HTTP " + e.getStatusCode().value() + " on " + op, e);
        } catch (PaynowApiException | PaynowApiTransientException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("paynow {} errored cause={}", op, e.toString());
            throw new PaynowApiTransientException("Unable to reach Paynow: " + e.getMessage(), e);
        }
    }

    static String formOf(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** The one cents-to-major rendering for this rail: {@code 9999} becomes {@code "99.99"}. */
    static String major(long amountCents) {
        return BigDecimal.valueOf(amountCents, 2).toPlainString();
    }

    private static BigDecimal amountOf(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether {@code url} names an allowed Paynow host. HTTPS only, except
     * that a base URL configured as plain http (a test double) allows its own
     * scheme, host and port.
     */
    public boolean isPaynowUrl(String url) {
        if (url == null || url.isBlank()) return false;
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        URI base = URI.create(baseUrl());
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return allowedHosts(base).contains(host) && (uri.getPort() == -1 || uri.getPort() == 443
                    || uri.getPort() == base.getPort());
        }
        return "http".equalsIgnoreCase(base.getScheme())
                && "http".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(base.getHost())
                && uri.getPort() == base.getPort();
    }

    private Set<String> allowedHosts(URI base) {
        Set<String> hosts = new LinkedHashSet<>();
        String configured = properties.getAllowedHosts();
        if (configured != null && !configured.isBlank()) {
            for (String h : configured.split(",")) {
                if (!h.isBlank()) hosts.add(h.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (hosts.isEmpty() && base.getHost() != null) {
            hosts.add(base.getHost().toLowerCase(Locale.ROOT));
        }
        return hosts;
    }

    static boolean isUsableAbsoluteUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.strip());
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private PaynowProperties.Integration requireIntegration(String currency) {
        return properties.integrationFor(currency).orElseThrow(() -> new IllegalStateException(
                "Paynow has no integration for " + currency + " on this deployment"));
    }

    private static String requireTrace(String merchantTrace) {
        if (merchantTrace == null || !TRACE_SHAPE.matcher(merchantTrace).matches()) {
            throw new IllegalArgumentException("merchant trace must be 32 lower-case hex characters");
        }
        return merchantTrace;
    }

    private String baseUrl() {
        String b = properties.getBaseUrl();
        if (b == null || b.isBlank()) b = "https://www.paynow.co.zw";
        b = b.strip();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** One line, no control or invisible characters, at most 200 characters; null when empty. */
    static String clean(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ")
                .replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }

    /** Registers {@link PaynowProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(PaynowProperties.class)
    static class PropertiesRegistrar {
    }
}
