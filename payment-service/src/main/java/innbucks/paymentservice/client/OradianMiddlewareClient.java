package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Talks to Oradian middleware: deposit-account ownership lookups for
 * POST /payments/transfer (via GET /internal/customers/{msisdn}/deposits)
 * and the actual transfer (POST /internal/transfers/deposit, which calls
 * Oradian's instafin.SubmitDepositAccountTransfer). Direct pod-to-pod —
 * never via the api-gateway. The X-Internal-Token shared secret comes from
 * {@code oradian-middleware.internal-token} (env {@code ORADIAN_INTERNAL_TOKEN})
 * and is DIFFERENT from {@code innbucks.internal-api-token} — the latter is
 * the loyalty-service secret. Mixing them up gets you a 401 from Oradian.
 *
 * Modelled after LoyaltyServiceClient so the wire conventions (JDK HttpClient
 * with explicit timeouts, correlation-id interceptor, RestClientResponseException
 * mapped to a typed domain exception that carries the upstream status) stay
 * uniform across all our S2S outbound calls.
 */
@Component
@Slf4j
public class OradianMiddlewareClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public OradianMiddlewareClient(
            @Value("${oradian-middleware.base-url:http://localhost:8090}") String baseUrl,
            @Value("${oradian-middleware.connect-timeout-ms:2000}") int connectMs,
            @Value("${oradian-middleware.read-timeout-ms:10000}") int readMs,
            @Value("${oradian-middleware.internal-token:}") String internalToken,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofMillis(readMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    public DepositTransferResponse submitDepositTransfer(DepositTransferRequest request) {
        try {
            DepositTransferResponse response = restClient.post()
                    .uri("/internal/transfers/deposit")
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(DepositTransferResponse.class);
            if (response == null) {
                // Treat an empty body as an upstream contract violation —
                // same shape as a 502 from the middleware.
                throw new OradianMiddlewareException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian deposit transfer succeeded from={} to={} txnID={}",
                    request.getFromAccountId(), request.getToAccountId(),
                    response.getTransactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("Oradian deposit transfer failed from={} to={} status={} detail={}",
                    request.getFromAccountId(), request.getToAccountId(),
                    e.getStatusCode().value(), detail);
            throw new OradianMiddlewareException(
                    "Oradian middleware rejected the deposit transfer: " + detail,
                    e.getStatusCode().value(), e);
        } catch (OradianMiddlewareException e) {
            // Re-throw the empty-body case unchanged — don't let the generic
            // catch below wrap it into a fresh OradianMiddlewareException.
            throw e;
        } catch (Exception e) {
            log.warn("Oradian deposit transfer errored from={} to={} cause={}",
                    request.getFromAccountId(), request.getToAccountId(), e.toString());
            throw new OradianMiddlewareException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Submit a withdrawal against an Oradian deposit account. Calls Oradian
     * middleware's /internal/transfers/withdraw, which proxies onto Oradian's
     * instafin.EnterWithdrawalOnDepositAccount. Same wire conventions as
     * {@link #submitDepositTransfer(DepositTransferRequest)} — RestClientResponseException
     * mapped onto OradianMiddlewareException with the upstream status preserved,
     * empty 200 body treated as a contract violation (502 fallback). The
     * upstream relays Oradian's VALIDATION message in the ProblemDetail
     * `detail` field; we surface it via parseErrorMessage.
     */
    public WithdrawalResponse submitWithdrawal(WithdrawalRequest request) {
        try {
            WithdrawalResponse response = restClient.post()
                    .uri("/internal/transfers/withdraw")
                    .header("X-Internal-Token", internalToken)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(WithdrawalResponse.class);
            if (response == null) {
                throw new OradianMiddlewareException(
                        "Oradian middleware returned an empty response body", 502);
            }
            log.info("Oradian withdrawal succeeded account={} amount={} txnID={}",
                    request.getAccountID(), request.getAmount(),
                    response.getTransactionID());
            return response;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("Oradian withdrawal failed account={} status={} detail={}",
                    request.getAccountID(), e.getStatusCode().value(), detail);
            throw new OradianMiddlewareException(
                    "Oradian middleware rejected the withdrawal: " + detail,
                    e.getStatusCode().value(), e);
        } catch (OradianMiddlewareException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Oradian withdrawal errored account={} cause={}",
                    request.getAccountID(), e.toString());
            throw new OradianMiddlewareException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Fetch the Oradian deposit accounts for a customer by msisdn. Used by
     * the public deposit-transfer endpoint to verify that the JWT-derived
     * caller actually owns the requested fromAccountId before forwarding the
     * transfer to Oradian.
     *
     * <p>An empty list (rather than a thrown exception) is the legitimate
     * shape when the customer has no Oradian accounts yet — callers map that
     * to a 403/400 ownership rejection on their own.
     */
    public List<DepositAccount> getDepositsForMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<DepositAccount> deposits = restClient.get()
                    .uri("/internal/customers/{msisdn}/deposits", msisdn)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DepositAccount>>() {});
            return deposits == null ? Collections.emptyList() : deposits;
        } catch (RestClientResponseException e) {
            String detail = parseErrorMessage(e.getResponseBodyAsString())
                    .orElse(e.getStatusText());
            log.warn("Oradian deposits lookup failed msisdn={} status={} detail={}",
                    msisdn, e.getStatusCode().value(), detail);
            throw new OradianMiddlewareException(
                    "Oradian middleware rejected the deposits lookup: " + detail,
                    e.getStatusCode().value(), e);
        } catch (Exception e) {
            log.warn("Oradian deposits lookup errored msisdn={} cause={}", msisdn, e.toString());
            throw new OradianMiddlewareException(
                    "Unable to reach Oradian middleware: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Pulls a human-readable detail out of a ProblemDetail / ApiResult error
     * body. Tries `detail` first (ProblemDetail's standard field), then
     * `title`, then `message` (our ApiResult envelope). Returns empty when
     * the body isn't parseable JSON — the caller falls back to statusText.
     */
    private Optional<String> parseErrorMessage(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            Object detail = parsed.get("detail");
            if (detail != null) return Optional.of(detail.toString());
            Object title = parsed.get("title");
            if (title != null) return Optional.of(title.toString());
            Object message = parsed.get("message");
            return Optional.ofNullable(message == null ? null : message.toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
