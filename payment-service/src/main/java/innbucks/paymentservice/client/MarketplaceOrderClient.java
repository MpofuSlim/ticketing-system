package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * S2S client for marketplace-service's internal order surface
 * ({@code /marketplace/internal/orders/*}: read, extend-expiry,
 * confirm-payment) — the marketplace twin of {@link BookingServiceClient},
 * used by the InnBucks 2D-code rail through {@code MarketplaceOrderGateway}.
 *
 * <p>Wire contract (see market-place {@code InternalOrderController} +
 * {@code docs/fleet-wiring.md} there): every call carries the fleet
 * {@code X-Internal-Token} (from {@code innbucks.internal-api-token});
 * responses ride the marketplace {@code ApiResult} envelope
 * ({@code {code, message, data}}). A REJECTED token answers 404 (deliberately
 * indistinguishable from an unknown ref — no existence oracle), so a
 * misconfigured token surfaces here as {@code order_not_found}.
 *
 * <p>Amounts are ALWAYS minor units (cents) on this surface — the marketplace
 * stores cents natively, so no conversion happens in this client or its
 * gateway. Confirm cross-checks the paid amount against the order total on
 * the marketplace side (422 {@code amount_mismatch} — the 100x guard's
 * confirm leg).
 *
 * <p>Encoding caveat (pinned by the contract test): {@code orderRef} rides as
 * a URI template variable, so any character outside the unreserved set —
 * including {@code :} and {@code /} — hits the wire percent-encoded
 * ({@code %3A}, {@code %2F}). Marketplace refs are {@code MKT-<hex>} today,
 * but WireMock stubs (and any proxy rules) must match the ENCODED form if
 * that ever changes.
 */
@Component
@Slf4j
public class MarketplaceOrderClient {

    /** The order fields the payment path consumes; everything else is ignored. */
    public record OrderView(
            String orderRef,
            String status,
            long totalCents,
            String currency,
            String buyerMsisdn,
            Instant expiresAt) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public MarketplaceOrderClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${marketplace-service.base-url:http://marketplace-service}") String baseUrl,
            @Value("${marketplace-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${marketplace-service.read-timeout-ms:5000}") int readMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        // JDK HttpClient supports PATCH (SimpleClientHttpRequestFactory's
        // HttpURLConnection rejects it); connect timeout on the HttpClient,
        // read timeout on the factory — same shape as BookingServiceClient.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        // Clone the load-balanced builder so "marketplace-service" resolves
        // through Eureka while this client keeps its own request factory.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /**
     * {@code GET /marketplace/internal/orders/{ref}} — amount to collect,
     * currency, buyer contact and status, resolved BEFORE minting a code.
     */
    public OrderView getOrder(String orderRef) {
        requireRef(orderRef);
        try {
            String body = restClient.get()
                    .uri("/marketplace/internal/orders/{ref}", orderRef)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> data = parseData(body);
            return new OrderView(
                    asString(data.get("orderRef")),
                    asString(data.get("status")),
                    asLong(data.get("totalCents")),
                    asString(data.get("currency")),
                    asString(data.get("buyerMsisdn")),
                    asInstant(data.get("expiresAt")));
        } catch (RestClientResponseException e) {
            throw refused("get", orderRef, e);
        } catch (MarketplaceOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("get", orderRef, e);
        }
    }

    /**
     * {@code PATCH /marketplace/internal/orders/{ref}/extend-expiry?minutes=N}
     * — make the stock hold provably outlive the payment code. The
     * marketplace accepts 1..60 and never shortens; out-of-range minutes are
     * refused HERE, before any network call, so a caller bug cannot turn into
     * a surprise 400 loop against the fleet.
     */
    public void extendExpiry(String orderRef, int minutes) {
        requireRef(orderRef);
        if (minutes < 1 || minutes > 60) {
            throw new MarketplaceOrderException(
                    "extend-expiry minutes must be between 1 and 60, got " + minutes,
                    400, "invalid_extension");
        }
        try {
            restClient.patch()
                    .uri("/marketplace/internal/orders/{ref}/extend-expiry?minutes={minutes}",
                            orderRef, minutes)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            log.debug("marketplace order expiry extended orderRef={} minutes={}", orderRef, minutes);
        } catch (RestClientResponseException e) {
            throw refused("extend-expiry", orderRef, e);
        } catch (MarketplaceOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("extend-expiry", orderRef, e);
        }
    }

    /**
     * {@code PATCH /marketplace/internal/orders/{ref}/confirm-payment} with
     * {@code {paymentRef, amountCents}} — marks the order PAID. Idempotent by
     * {@code paymentRef} (same-ref replay = 200 no-op); the marketplace
     * cross-checks {@code amountCents} against the order total (422
     * {@code amount_mismatch}) and refuses CANCELLED/EXPIRED orders (409
     * {@code order_not_confirmable}). Guard rails here: a blank paymentRef or
     * non-positive amount never reaches the network.
     */
    public void confirmPayment(String orderRef, String paymentRef, long amountCents) {
        requireRef(orderRef);
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new MarketplaceOrderException(
                    "confirm-payment requires a non-blank paymentRef", 400, "payment_ref_required");
        }
        if (amountCents <= 0) {
            throw new MarketplaceOrderException(
                    "confirm-payment requires a positive amountCents, got " + amountCents,
                    400, "invalid_amount");
        }
        try {
            restClient.patch()
                    .uri("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(Map.of("paymentRef", paymentRef, "amountCents", amountCents))
                    .retrieve()
                    .body(String.class);
            log.info("marketplace order confirmed orderRef={} paymentRef={} amountCents={}",
                    orderRef, paymentRef, amountCents);
        } catch (RestClientResponseException e) {
            throw refused("confirm-payment", orderRef, e);
        } catch (MarketplaceOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("confirm-payment", orderRef, e);
        }
    }

    // ---------------------------------------------------------------------

    private static void requireRef(String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            throw new MarketplaceOrderException(
                    "orderRef must not be blank", 400, "order_ref_required");
        }
    }

    private Map<String, Object> parseData(String body) {
        try {
            ApiResult<Map<String, Object>> envelope = objectMapper.readValue(
                    body, new TypeReference<ApiResult<Map<String, Object>>>() {});
            if (envelope.getData() == null) {
                throw new MarketplaceOrderException(
                        "marketplace-service answered without order data", 502, "marketplace_bad_envelope");
            }
            return envelope.getData();
        } catch (MarketplaceOrderException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceOrderException(
                    "marketplace-service envelope could not be parsed: " + e.getMessage(),
                    502, "marketplace_bad_envelope");
        }
    }

    /** Non-2xx from the marketplace: relay its status + envelope code/message. */
    private MarketplaceOrderException refused(String op, String orderRef, RestClientResponseException e) {
        String code = null;
        String message = e.getStatusText();
        try {
            ApiResult<Object> envelope = objectMapper.readValue(
                    e.getResponseBodyAsString(), new TypeReference<ApiResult<Object>>() {});
            if (envelope.getCode() != null) code = envelope.getCode();
            if (envelope.getMessage() != null) message = envelope.getMessage();
        } catch (Exception ignored) {
            // keep the status text
        }
        log.warn("marketplace-service {} refused orderRef={} status={} code={} detail={}",
                op, orderRef, e.getStatusCode().value(), code, message);
        return new MarketplaceOrderException(message, e.getStatusCode().value(), code);
    }

    private MarketplaceOrderException unreachable(String op, String orderRef, Exception e) {
        log.warn("marketplace-service {} errored orderRef={} cause={}", op, orderRef, e.toString());
        return new MarketplaceOrderException(
                "Unable to reach marketplace-service for the order", 503, "marketplace_unreachable");
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0L;
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static Instant asInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Non-2xx / unreachable outcome of a marketplace call. {@code statusCode}
     * carries the HTTP status (503 for connect/read failures); {@code code}
     * the marketplace envelope's machine code when one was parsable (e.g.
     * {@code amount_mismatch}, {@code order_not_confirmable},
     * {@code order_not_found}).
     */
    public static class MarketplaceOrderException extends RuntimeException {
        private final int statusCode;
        private final String code;

        public MarketplaceOrderException(String message, int statusCode, String code) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int getStatusCode() { return statusCode; }

        public String getCode() { return code; }
    }
}
