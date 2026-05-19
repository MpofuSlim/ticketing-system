package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the Resilience4j Retry + CircuitBreaker semantics on
 * {@link OradianMiddlewareClient}. Uses {@link MockRestServiceServer} to
 * mock the upstream HTTP layer at the RestClient seam so we exercise the
 * real retry / classification logic, not a mock of the client itself.
 *
 * <p>Construction is unusual for this codebase: we don't go through
 * Spring's bean wiring (the @Value-injected URLs / timeouts), instead
 * subclassing inline so the {@code RestClient.Builder} can be intercepted
 * by {@code MockRestServiceServer.bindTo(builder)}. Test-only seam.
 */
class OradianMiddlewareClientResilienceTest {

    /** A RetryConfig that fires only on the transient subclass — same as application.yaml. */
    private static RetryConfig retryConfigForTests(int maxAttempts) {
        return RetryConfig.<Object>custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1)) // keep tests fast
                .retryExceptions(OradianMiddlewareTransientException.class)
                .build();
    }

    /** A CircuitBreaker that's effectively disabled for these tests (never opens). */
    private static CircuitBreakerConfig openCircuitConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100.0f)
                .build();
    }

    /**
     * Builds the client wired to a {@link MockRestServiceServer} so the test
     * can stage upstream responses. Returns (client, server) so the caller
     * can call {@code server.expect(...)} before invoking the client.
     */
    private record Wired(OradianMiddlewareClient client, MockRestServiceServer server) {}

    private static Wired wired(RetryRegistry retries, CircuitBreakerRegistry breakers) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://oradian.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper();
        // Subclass so we can inject the pre-built RestClient. The production
        // constructor builds its own from URL + timeouts via JdkClientHttpRequestFactory;
        // that path isn't what we're testing here.
        OradianMiddlewareClient client = new OradianMiddlewareClient(
                "http://oradian.test", 100, 100, "test-token",
                mapper, retries, breakers) {};
        // Reflect in the test-only RestClient (the one bound to the mock server).
        org.springframework.test.util.ReflectionTestUtils.setField(client, "restClient", builder.build());
        return new Wired(client, server);
    }

    private static DepositTransferRequest depositRequest() {
        return DepositTransferRequest.builder()
                .fromAccountId("A000001")
                .toAccountId("A000002")
                .amount("123.00")
                .notes("")
                .transactionDate(LocalDate.of(2026, 5, 19))
                .build();
    }

    @Test
    void submitDepositTransfer_retriesOnTransientUpstream5xx_andSucceedsOnceUpstreamRecovers() {
        RetryRegistry retries = RetryRegistry.of(retryConfigForTests(3));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(openCircuitConfig());
        Wired w = wired(retries, breakers);

        // First two attempts: 503 (transient). Third: success.
        w.server.expect(ExpectedCount.times(2),
                        requestTo("http://oradian.test/internal/transfers/deposit"))
                .andRespond(withServerError().contentType(APPLICATION_JSON)
                        .body("{\"detail\":\"upstream temporarily down\"}"));
        w.server.expect(ExpectedCount.once(),
                        requestTo("http://oradian.test/internal/transfers/deposit"))
                .andRespond(withSuccess(
                        "{\"transactionID\":\"1155\",\"referenceNumber\":\"ref-1\"}",
                        APPLICATION_JSON));

        DepositTransferResponse response = w.client.submitDepositTransfer(depositRequest());

        assertEquals("1155", response.getTransactionID(),
                "Retry must have recovered from the first two 503s and surfaced the eventual 200");
        w.server.verify();
    }

    @Test
    void submitDepositTransfer_doesNotRetryOn4xx_evenWhenRetryAllowsMultipleAttempts() {
        // 4xx is a permanent rejection (validation, ownership, insufficient
        // funds). Retrying would just delay the customer-facing error AND
        // waste an Oradian round-trip. The retry config retries only on
        // OradianMiddlewareTransientException — 4xx throws the plain
        // superclass and bails after one attempt.
        RetryRegistry retries = RetryRegistry.of(retryConfigForTests(5));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(openCircuitConfig());
        Wired w = wired(retries, breakers);

        AtomicInteger upstreamHits = new AtomicInteger();
        w.server.expect(ExpectedCount.once(),
                        requestTo("http://oradian.test/internal/transfers/deposit"))
                .andRespond(req -> {
                    upstreamHits.incrementAndGet();
                    return withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(APPLICATION_JSON)
                            .body("{\"detail\":\"Insufficient funds\"}")
                            .createResponse(req);
                });

        OradianMiddlewareException ex = assertThrows(OradianMiddlewareException.class,
                () -> w.client.submitDepositTransfer(depositRequest()));

        assertEquals(422, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Insufficient funds"));
        assertFalse(ex instanceof OradianMiddlewareTransientException,
                "4xx MUST throw the non-transient class so Retry's retry-exceptions filter " +
                        "doesn't fire and waste attempts on a permanent rejection");
        assertEquals(1, upstreamHits.get(), "Oradian must be called exactly once for a 4xx");
        w.server.verify();
    }

    @Test
    void submitDepositTransfer_exhaustsRetries_thenSurfacesTheLastTransientException() {
        RetryRegistry retries = RetryRegistry.of(retryConfigForTests(3));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(openCircuitConfig());
        Wired w = wired(retries, breakers);

        // All 3 attempts: 502. Customer sees the final exception with the
        // upstream's status code intact.
        w.server.expect(ExpectedCount.times(3),
                        requestTo("http://oradian.test/internal/transfers/deposit"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                        .contentType(APPLICATION_JSON).body("{}"));

        OradianMiddlewareException ex = assertThrows(OradianMiddlewareException.class,
                () -> w.client.submitDepositTransfer(depositRequest()));

        assertEquals(502, ex.getStatusCode());
        assertInstanceOf(OradianMiddlewareTransientException.class, ex,
                "5xx after all retries exhausted must still be the transient subclass — " +
                        "GlobalExceptionHandler reads the status code for the customer response");
        w.server.verify();
    }

    @Test
    void getDepositsForMsisdn_emptyMsisdnShortCircuits_withoutCallingUpstreamOrRetry() {
        // Defensive: a blank/null msisdn means we have no customer to look
        // up. Returning [] is the correct ownership-check semantics (no
        // accounts -> 403 at the controller), AND it must NOT burn an
        // upstream call + retry budget on a guaranteed-fail input.
        RetryRegistry retries = RetryRegistry.of(retryConfigForTests(3));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(openCircuitConfig());
        Wired w = wired(retries, breakers);

        // No expectations set on the server. If the client calls upstream,
        // MockRestServiceServer fails the test.
        List<DepositAccount> nullCase = w.client.getDepositsForMsisdn(null);
        List<DepositAccount> blankCase = w.client.getDepositsForMsisdn("  ");

        assertTrue(nullCase.isEmpty());
        assertTrue(blankCase.isEmpty());
        w.server.verify();
    }
}
