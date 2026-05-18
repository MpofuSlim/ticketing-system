package innbucks.paymentservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.CorrelationIdPropagatingInterceptor;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Calls Oradian middleware's POST /internal/transfers/deposit to submit a
 * deposit account transfer via Oradian's instafin.SubmitDepositAccountTransfer.
 * Direct pod-to-pod — the request never traverses the api-gateway; the
 * X-Internal-Token shared secret is the only thing the middleware checks.
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
            @Value("${innbucks.internal-api-token:}") String internalToken,
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
