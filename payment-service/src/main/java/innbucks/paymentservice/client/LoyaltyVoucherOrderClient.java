package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * S2S client for loyalty-service's internal voucher purchase-order surface
 * ({@code /loyalty/internal/voucher-orders/*}: read, extend-expiry,
 * confirm-payment) — the voucher twin of {@link MarketplaceOrderClient}, used
 * by all three payment rails through {@code LoyaltyVoucherOrderGateway}.
 *
 * <p>Wire contract (InnRewards V47, {@code InternalVoucherOrderController}):
 * every call carries the fleet {@code X-Internal-Token}; a bad/missing token
 * answers a bodyless 401. Responses are loyalty's internal PLAIN MAPS — not
 * the ApiResult envelope the marketplace uses — and errors are
 * {@code {code, message}} maps. Amounts are DECIMAL MAJOR UNITS here
 * (loyalty's own money representation); the gateway is this product's
 * major↔minor conversion point, per the OrderGateway contract. Loyalty
 * guarantees whole-cent order amounts at creation, so the conversion is
 * always exact.
 */
@Component
@Slf4j
public class LoyaltyVoucherOrderClient {

    /** The order fields the payment path consumes; everything else is ignored. */
    public record VoucherOrderView(
            String orderRef,
            String status,
            BigDecimal amount,
            String currency,
            String payerMsisdn,
            Instant expiresAt,
            boolean payable) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public LoyaltyVoucherOrderClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${loyalty-service.base-url:http://loyalty-service}") String baseUrl,
            @Value("${loyalty-service.connect-timeout-ms:2000}") int connectMs,
            @Value("${loyalty-service.read-timeout-ms:5000}") int readMs,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            ObjectMapper objectMapper) {
        // JDK HttpClient supports PATCH; connect timeout on the HttpClient,
        // read timeout on the factory — same shape as MarketplaceOrderClient.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /** {@code GET /loyalty/internal/voucher-orders/{ref}} — amount to collect,
     *  currency, payer contact (the EcoCash prompt target) and payability. */
    public VoucherOrderView getOrder(String orderRef) {
        requireRef(orderRef);
        try {
            String body = restClient.get()
                    .uri("/loyalty/internal/voucher-orders/{ref}", orderRef)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            return parseView(body);
        } catch (RestClientResponseException e) {
            throw refused("get", orderRef, e);
        } catch (VoucherOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("get", orderRef, e);
        }
    }

    /**
     * {@code PATCH .../extend-expiry?minutes=N} — make the order's payable
     * window provably outlive the payment instrument. Loyalty accepts 1..60
     * and never shortens; out-of-range minutes are refused HERE, before any
     * network call.
     */
    public void extendExpiry(String orderRef, int minutes) {
        requireRef(orderRef);
        if (minutes < 1 || minutes > 60) {
            throw new VoucherOrderException(
                    "extend-expiry minutes must be between 1 and 60, got " + minutes,
                    400, "INVALID_EXTENSION");
        }
        try {
            restClient.patch()
                    .uri("/loyalty/internal/voucher-orders/{ref}/extend-expiry?minutes={minutes}",
                            orderRef, minutes)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            log.debug("voucher order expiry extended orderRef={} minutes={}", orderRef, minutes);
        } catch (RestClientResponseException e) {
            throw refused("extend-expiry", orderRef, e);
        } catch (VoucherOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("extend-expiry", orderRef, e);
        }
    }

    /**
     * {@code PATCH .../confirm-payment} with {@code {paymentRef, amountCents}}
     * — marks the order PAID and ISSUES the voucher (a 200 means the voucher
     * exists). Idempotent by {@code paymentRef}; loyalty cross-checks
     * {@code amountCents} against the order's frozen amount (422
     * {@code AMOUNT_MISMATCH} — the 100x guard's confirm leg) and refuses a
     * cancelled or differently-paid order (409). Guard rails here: a blank
     * paymentRef or non-positive amount never reaches the network.
     */
    public void confirmPayment(String orderRef, String paymentRef, long amountCents) {
        requireRef(orderRef);
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new VoucherOrderException(
                    "confirm-payment requires a non-blank paymentRef", 400, "PAYMENT_REF_REQUIRED");
        }
        if (amountCents <= 0) {
            throw new VoucherOrderException(
                    "confirm-payment requires a positive amountCents, got " + amountCents,
                    400, "INVALID_AMOUNT");
        }
        try {
            restClient.patch()
                    .uri("/loyalty/internal/voucher-orders/{ref}/confirm-payment", orderRef)
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(Map.of("paymentRef", paymentRef, "amountCents", amountCents))
                    .retrieve()
                    .body(String.class);
            log.info("voucher order confirmed orderRef={} paymentRef={} amountCents={}",
                    orderRef, paymentRef, amountCents);
        } catch (RestClientResponseException e) {
            throw refused("confirm-payment", orderRef, e);
        } catch (VoucherOrderException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable("confirm-payment", orderRef, e);
        }
    }

    // ---------------------------------------------------------------------

    private static void requireRef(String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            throw new VoucherOrderException("orderRef must not be blank", 400, "ORDER_REF_REQUIRED");
        }
    }

    private VoucherOrderView parseView(String body) {
        try {
            Map<String, Object> data = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            return new VoucherOrderView(
                    asString(data.get("orderRef")),
                    asString(data.get("status")),
                    asBigDecimal(data.get("amount")),
                    asString(data.get("currency")),
                    asString(data.get("payerMsisdn")),
                    asInstant(data.get("expiresAt")),
                    Boolean.TRUE.equals(data.get("payable")));
        } catch (Exception e) {
            throw new VoucherOrderException(
                    "loyalty-service order response could not be parsed: " + e.getMessage(),
                    502, "LOYALTY_BAD_ENVELOPE");
        }
    }

    /** Non-2xx from loyalty: relay its status + {code,message} map. A 401 here
     *  is a misconfigured internal token, not an unknown order. */
    private VoucherOrderException refused(String op, String orderRef, RestClientResponseException e) {
        String code = null;
        String message = e.getStatusText();
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    e.getResponseBodyAsString(), new TypeReference<Map<String, Object>>() {});
            if (parsed.get("code") != null) code = parsed.get("code").toString();
            if (parsed.get("message") != null) message = parsed.get("message").toString();
        } catch (Exception ignored) {
            // keep the status text (the 401 answers with no body at all)
        }
        log.warn("loyalty-service voucher-order {} refused orderRef={} status={} code={} detail={}",
                op, orderRef, e.getStatusCode().value(), code, message);
        return new VoucherOrderException(message, e.getStatusCode().value(), code);
    }

    private VoucherOrderException unreachable(String op, String orderRef, Exception e) {
        log.warn("loyalty-service voucher-order {} errored orderRef={} cause={}", op, orderRef, e.toString());
        return new VoucherOrderException(
                "Unable to reach loyalty-service for the voucher order", 503, "LOYALTY_UNREACHABLE");
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
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
     * Non-2xx / unreachable outcome of a loyalty voucher-order call.
     * {@code statusCode} carries the HTTP status (503 for connect/read
     * failures); {@code code} loyalty's machine code when parsable (e.g.
     * {@code AMOUNT_MISMATCH}, {@code ORDER_ALREADY_PAID},
     * {@code ORDER_NOT_EXTENDABLE}).
     */
    public static class VoucherOrderException extends RuntimeException {
        private final int statusCode;
        private final String code;

        public VoucherOrderException(String message, int statusCode, String code) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
        }

        public int getStatusCode() { return statusCode; }

        public String getCode() { return code; }
    }
}
