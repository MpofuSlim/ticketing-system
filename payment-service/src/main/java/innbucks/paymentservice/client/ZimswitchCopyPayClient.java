package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Client for <b>ZimSwitch Online (COPYandPAY)</b> — the card rail behind
 * {@code POST /payments}. Spec pinned at
 * {@code docs/api/zimswitch-copyandpay.md} (distilled from
 * zimswitch.docs.oppwa.com). ZimSwitch Online is a white-label of ACI's OPPWA
 * platform, so the wire contract is the standard COPYandPAY one.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code POST /v1/checkouts} — prepare a checkout the FE widget renders
 *       against. <b>Never retried</b>: a retry after an ambiguous failure
 *       would mint a SECOND live checkout while the first may still render a
 *       payable form, splitting our tracking from the checkout the shopper
 *       might pay. A failed/timed-out prepare moves no money, so the caller
 *       closes the ledger row FAILED and the customer simply taps pay again.
 *       Circuit breaker still applies.</li>
 *   <li>{@code GET /v1/checkouts/{id}/payment} — the payment status.
 *       Read-only, so retried on transients — but throttled upstream to TWO
 *       reads per checkout per minute (callers gate on the row's
 *       {@code card_status_checked_at} stamp), and the read of a FINAL
 *       outcome is ONE-SHOT: after it the checkout id is dead and answers
 *       {@code 200.300.404}. Callers must persist the outcome in the same
 *       breath they consume it.</li>
 * </ul>
 *
 * <p>Wire conventions (per the doc): requests are FORM-ENCODED (all
 * parameters in the POST body, never the URL), auth is
 * {@code Authorization: Bearer} plus {@code entityId} as a parameter, and
 * <b>amounts are MAJOR units with exactly two decimals</b> ({@code "92.00"})
 * — the OPPOSITE of the InnBucks Merchant API's cents. This client takes
 * {@code long amountCents} anyway (the service layer's universal unit) and
 * owns the one cents→major rendering for this rail; the caller cross-checks
 * the status response's amount echo (the 100x guard).
 *
 * <p><b>SSRF guard:</b> the status URL is ALWAYS rebuilt here from the
 * checkoutId the caller stored at prepare time. The browser-supplied
 * {@code resourcePath} redirect parameter is never accepted — concatenating
 * attacker-controllable input into a Bearer-authenticated server-side URL is
 * the documented-but-unsafe path this client refuses by construction; the
 * defensive {@link #CHECKOUT_ID_SHAPE} check enforces it even against our
 * own stored values.
 */
@Slf4j
@Component
public class ZimswitchCopyPayClient {

    private static final String RESILIENCE_INSTANCE_NAME = "zimswitch";
    private static final String CHECKOUTS_PATH = "/v1/checkouts";

    /**
     * Legal shape of a COPYandPAY checkout id (hex-ish opaque token). Anything
     * else never reaches the wire — the status path is built from this value.
     */
    private static final Pattern CHECKOUT_ID_SHAPE = Pattern.compile("^[A-Za-z0-9.\\-]{8,64}$");

    private final ZimswitchProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ZimswitchCopyPayClient(ZimswitchProperties properties,
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
                .baseUrl(properties.getBaseUrl() == null ? "http://localhost" : properties.getBaseUrl())
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME);
    }

    /** True when base-url + entity id + access token are present. */
    public boolean isConfigured() {
        return notBlank(properties.getBaseUrl()) && notBlank(properties.getEntityId())
                && notBlank(properties.getAccessToken());
    }

    /** Absolute widget script URL for a checkout — the FE loads this with the SRI digest. */
    public String widgetScriptUrl(String checkoutId) {
        return trimTrailingSlash(properties.getBaseUrl()) + "/v1/paymentWidgets.js?checkoutId=" + checkoutId;
    }

    /**
     * Prepare a COPYandPAY checkout for {@code amountCents}. Single attempt
     * (circuit breaker only — see class javadoc). A response without
     * {@code 000.200.100}+id comes back {@code created=false} with the
     * upstream reason; transport-level failures throw
     * {@link ZimswitchApiTransientException}.
     *
     * @param merchantTransactionId our {@code TKT-PMT-<uuid>}-style payment
     *                              reference — the handle that ties gateway
     *                              transactions back to the ledger row
     */
    public CheckoutPreparation prepareCheckout(String merchantTransactionId,
                                               long amountCents,
                                               String currency) {
        requireConfigured();
        Supplier<CheckoutPreparation> call =
                () -> doPrepare(merchantTransactionId, amountCents, currency);
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
        } catch (CallNotPermittedException e) {
            throw new ZimswitchApiTransientException(
                    "ZimSwitch gateway is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private CheckoutPreparation doPrepare(String merchantTransactionId, long amountCents, String currency) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("entityId", properties.getEntityId());
        // The one cents -> major-units rendering for this rail ("92.00").
        form.add("amount", BigDecimal.valueOf(amountCents, 2).toPlainString());
        form.add("currency", currency);
        form.add("paymentType", "DB");
        form.add("merchantTransactionId", merchantTransactionId);
        if (properties.isRequestIntegrity()) {
            form.add("integrity", "true");
        }
        if (notBlank(properties.getTestMode())) {
            form.add("testMode", properties.getTestMode());
        }
        try {
            String raw = restClient.post()
                    .uri(CHECKOUTS_PATH)
                    .header("Authorization", "Bearer " + properties.getAccessToken())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            CheckoutPreparation prepared = classifyPreparation(raw);
            log.info("zimswitch checkout prepare merchantTransactionId={} created={} checkoutId={} resultCode={}",
                    merchantTransactionId, prepared.created(), prepared.checkoutId(), prepared.resultCode());
            return prepared;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 500) {
                // Outcome ambiguous (a checkout MAY exist upstream) but no
                // money moves on prepare and an undelivered checkout expires
                // within 30 minutes — the caller closes the row FAILED, never
                // retries.
                throw new ZimswitchApiTransientException(
                        "ZimSwitch returned HTTP " + status + " on checkout preparation", status, e);
            }
            // 4xx: the gateway actively refused. Surface the envelope's
            // result.code/description when the body carries one.
            CheckoutPreparation refused = classifyPreparation(e.getResponseBodyAsString());
            if (refused.resultCode() != null) {
                log.warn("zimswitch refused checkout preparation merchantTransactionId={} status={} resultCode={} msg={}",
                        merchantTransactionId, status, refused.resultCode(), refused.resultMessage());
                return refused;
            }
            log.error("zimswitch rejected checkout preparation merchantTransactionId={} status={} body={}",
                    merchantTransactionId, status, truncate(e.getResponseBodyAsString(), 300));
            throw new ZimswitchApiException(
                    "ZimSwitch rejected the checkout preparation: HTTP " + status, status, e);
        } catch (ZimswitchApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("zimswitch checkout preparation errored merchantTransactionId={} cause={}",
                    merchantTransactionId, e.toString());
            throw new ZimswitchApiTransientException(
                    "Unable to reach the ZimSwitch gateway: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Payment status for a checkout WE prepared. The path is rebuilt from the
     * stored checkoutId — never from a browser-supplied {@code resourcePath}.
     * Read-only, retried on transients; callers gate call frequency on the
     * row's {@code card_status_checked_at} (two per checkout per minute) and
     * persist any terminal outcome immediately (the read is one-shot).
     */
    public CardPaymentStatus getPaymentStatus(String checkoutId) {
        requireConfigured();
        if (checkoutId == null || !CHECKOUT_ID_SHAPE.matcher(checkoutId).matches()) {
            // Never let a malformed handle shape a URL that carries our
            // Bearer token. This is a code bug or row tampering, not a
            // customer condition.
            throw new ZimswitchApiException(
                    "Refusing status query for malformed checkoutId", 400);
        }
        return executeIdempotent(() -> {
            try {
                String raw = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path(CHECKOUTS_PATH + "/{id}/payment")
                                .queryParam("entityId", properties.getEntityId())
                                .build(checkoutId))
                        .header("Authorization", "Bearer " + properties.getAccessToken())
                        .retrieve()
                        .body(String.class);
                return classifyStatus(raw);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status >= 500) {
                    throw new ZimswitchApiTransientException(
                            "ZimSwitch returned HTTP " + status + " on payment status", status, e);
                }
                // 4xx with a parseable envelope is a REAL answer, not a
                // transport failure — most importantly 200.300.404 ("no
                // payment session"), the gateway's normal reply while the
                // shopper still has the form open. Classify it.
                CardPaymentStatus classified = classifyStatus(e.getResponseBodyAsString());
                if (classified.resultCode() != null) {
                    return classified;
                }
                throw new ZimswitchApiException(
                        "ZimSwitch rejected the payment-status request: HTTP " + status, status, e);
            } catch (ZimswitchApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ZimswitchApiTransientException(
                        "Unable to reach the ZimSwitch gateway: " + e.getMessage(), 502, e);
            }
        });
    }

    private CheckoutPreparation classifyPreparation(String raw) {
        Map<String, Object> parsed = parseJson(raw);
        String resultCode = resultCodeOf(parsed);
        String resultMessage = resultDescriptionOf(parsed);
        String id = asString(parsed.get("id"));
        String integrity = asString(parsed.get("integrity"));
        String ndc = asString(parsed.get("ndc"));
        // Created only on the documented success code AND an actual id —
        // without the id there is no form to render.
        boolean created = ZimswitchResultCode.CHECKOUT_CREATED.equals(resultCode) && notBlank(id);
        if (ZimswitchResultCode.CHECKOUT_CREATED.equals(resultCode) && !created) {
            log.error("zimswitch prepare said success but returned no checkout id — keys={}", parsed.keySet());
        }
        if (created && integrity == null && properties.isRequestIntegrity()) {
            // We asked for an SRI digest and did not get one: the FE will
            // load the card-entry script unpinned. Downgrade, not failure —
            // but loudly, because A08 says pin everything immutable.
            log.warn("zimswitch prepare returned no integrity digest despite integrity=true checkoutId={}", id);
        }
        return new CheckoutPreparation(created, id, integrity, resultCode, resultMessage, ndc);
    }

    private CardPaymentStatus classifyStatus(String raw) {
        Map<String, Object> parsed = parseJson(raw);
        String resultCode = resultCodeOf(parsed);
        return new CardPaymentStatus(
                ZimswitchResultCode.classify(resultCode),
                asString(parsed.get("id")),
                parseMajorAmount(parsed.get("amount")),
                asString(parsed.get("currency")),
                asString(parsed.get("paymentBrand")),
                asString(parsed.get("paymentType")),
                asString(parsed.get("merchantTransactionId")),
                resultCode,
                resultDescriptionOf(parsed),
                asString(parsed.get("ndc")));
    }

    @SuppressWarnings("unchecked")
    private static String resultCodeOf(Map<String, Object> parsed) {
        Object result = parsed.get("result");
        if (result instanceof Map<?, ?> m) {
            return asString(((Map<String, Object>) m).get("code"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String resultDescriptionOf(Map<String, Object> parsed) {
        Object result = parsed.get("result");
        if (result instanceof Map<?, ?> m) {
            return asString(((Map<String, Object>) m).get("description"));
        }
        return null;
    }

    /** Amounts on this rail are major-unit decimal strings ("92.00"). */
    private static BigDecimal parseMajorAmount(Object value) {
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private <T> T executeIdempotent(Supplier<T> supplier) {
        Supplier<T> withCb = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
        Supplier<T> decorated = Retry.decorateSupplier(retry, withCb);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new ZimswitchApiTransientException(
                    "ZimSwitch gateway is temporarily unavailable (circuit open)", 503, e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ZimswitchApiException(
                    "ZimSwitch is not configured — set ZIMSWITCH_BASE_URL/ZIMSWITCH_ENTITY_ID/ZIMSWITCH_ACCESS_TOKEN", 503);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Registers {@link ZimswitchProperties} for binding. */
    @Configuration
    @EnableConfigurationProperties(ZimswitchProperties.class)
    static class ZimswitchPropertiesConfiguration { }
}
