package innbucks.paymentservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import innbucks.paymentservice.config.PooledHttpClient;
import innbucks.paymentservice.config.PooledHttpClientProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link PaynowClient} ({@code docs/api/paynow.md}).
 *
 * <p><b>Stub provenance:</b> the shapes are Paynow's own documented examples
 * (initiate Ok and Error, the status update, trace NotFound and Error, the
 * O'mari {@code Invalid OTP} error), re-signed with this test's key because
 * the docs' examples are truncated. The capitalised keys of Paynow's initiate
 * example ({@code Status=Ok&BrowserUrl=…}) are kept on purpose: they prove the
 * client reads keys in any case. Replace these with real staging replies once
 * the integration is in test mode.
 *
 * <p>Pure JUnit + WireMock. The retry registry has 2 attempts, so the policy
 * is visible: poll and trace retry, initiate and the O'mari code never do.
 */
class PaynowClientContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String KEY = "3e9fed89-60e1-4ce5-ab6e-6b1eb2d4f977";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String REFERENCE = "TKZ-AMA2KFESTIVA-CF604A7376B7";

    private static WireMockServer wireMock;
    private static PaynowClient client;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = newClient(wireMock.baseUrl());
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
        POOL.close();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    static PaynowClient newClient(String baseUrl) {
        return newClient(baseUrl, CircuitBreakerRegistry.ofDefaults());
    }

    static PaynowClient newClient(String baseUrl, CircuitBreakerRegistry breakers) {
        PaynowProperties props = new PaynowProperties();
        props.setBaseUrl(baseUrl);
        props.setUsdIntegrationId("1201");
        props.setUsdIntegrationKey(KEY);
        props.setResultUrl("https://tickets.example.co.zw/foundry/payments/paynow/result");
        props.setReturnUrl("https://ticketize.example.co.zw/checkout/paynow-return");
        props.setAuthEmail("payments@example.co.zw");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(PaynowApiTransientException.class)
                .build());
        return new PaynowClient(props, retries, breakers, POOL);
    }

    /** A Paynow reply signed with {@link #KEY}, the way Paynow signs. */
    private static String signed(String body) {
        return body + "&hash=" + PaynowHash.expectedFor(PaynowMessage.parse(body), KEY);
    }

    private static String pollUrl() {
        return wireMock.baseUrl() + "/Interface/CheckPayment/?guid=c7ed41da-0159-46da-b428-69549f770413";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void stubPost(String path, int status, String body) {
        wireMock.stubFor(post(urlPathEqualTo(path)).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "text/html; charset=utf-8").withBody(body)));
    }

    private static PaynowMessage sent(String path) {
        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        return PaynowMessage.parse(requests.get(0).getBodyAsString());
    }

    // --- initiate (web) ---------------------------------------------------

    @Test
    @DisplayName("web initiate Ok: Paynow's capitalised keys read, hash checked, page and poll URLs returned")
    void initiateWeb_ok() {
        stubPost(PaynowClient.INITIATE_PATH, 200, signed("Status=Ok&BrowserUrl=" + enc(wireMock.baseUrl()
                + "/Payment/ConfirmPayment/9510") + "&PollUrl=" + enc(pollUrl()) + "&PaynowReference=9510"));

        PaynowInitiation init = client.initiateWeb("USD", REFERENCE, 1250, "Ticketize tickets",
                "https://ticketize.example.co.zw/checkout/paynow-return?transactionId=t1", TRACE);

        assertThat(init.browserUrl()).isEqualTo(wireMock.baseUrl() + "/Payment/ConfirmPayment/9510");
        assertThat(init.pollUrl()).isEqualTo(pollUrl());
        assertThat(init.paynowReference()).isEqualTo("9510");

        PaynowMessage request = sent(PaynowClient.INITIATE_PATH);
        assertThat(request.get("id")).isEqualTo("1201");
        assertThat(request.get("reference")).isEqualTo(REFERENCE);
        assertThat(request.get("amount")).isEqualTo("12.50");
        assertThat(request.get("additionalinfo")).isEqualTo("Ticketize tickets");
        assertThat(request.get("returnurl")).endsWith("?transactionId=t1");
        assertThat(request.get("resulturl")).isEqualTo("https://tickets.example.co.zw/foundry/payments/paynow/result");
        assertThat(request.get("merchanttrace")).isEqualTo(TRACE);
        assertThat(request.get("status")).isEqualTo("Message");
        assertThat(request.get("authemail")).as("web checkout sends no authemail (test mode would refuse it)").isNull();
        assertThat(PaynowHash.verify(request, KEY)).as("we sign what we send").isTrue();
        wireMock.verify(postRequestedFor(urlPathEqualTo(PaynowClient.INITIATE_PATH))
                .withHeader("User-Agent", equalTo(PaynowClient.USER_AGENT))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withoutHeader("traceparent"));
    }

    @Test
    @DisplayName("web initiate Error: refused, Paynow's words kept, nothing to trace")
    void initiateWeb_error() {
        stubPost(PaynowClient.INITIATE_PATH, 200, "Status=Error&Error=Invalid+amount+field");

        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiException.class)
                .satisfies(e -> assertThat(((PaynowApiException) e).paynowError()).isEqualTo("Invalid amount field"));
    }

    @Test
    @DisplayName("an Ok whose hash does not verify is no answer: never redirect on it")
    void initiateWeb_badHash() {
        stubPost(PaynowClient.INITIATE_PATH, 200, "status=Ok&browserurl=" + enc(wireMock.baseUrl() + "/p")
                + "&pollurl=" + enc(pollUrl()) + "&hash=ABC");

        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class)
                .hasMessageContaining("hash");
    }

    @Test
    @DisplayName("an Ok naming a page or poll URL off Paynow's hosts is no answer")
    void initiateWeb_offHostUrls() {
        stubPost(PaynowClient.INITIATE_PATH, 200, signed("status=Ok&browserurl=" + enc("https://evil.example/pay")
                + "&pollurl=" + enc(pollUrl())));
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class);

        wireMock.resetAll();
        stubPost(PaynowClient.INITIATE_PATH, 200, signed("status=Ok&browserurl=" + enc(wireMock.baseUrl() + "/p")
                + "&pollurl=" + enc("http://169.254.169.254/latest/meta-data")));
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class);
    }

    @Test
    @DisplayName("initiate on a 5xx, an HTML page or a dead port: no answer, and sent ONCE (never retried)")
    void initiate_noAnswer_neverRetried() {
        stubPost(PaynowClient.INITIATE_PATH, 503, "Service Unavailable");
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PaynowClient.INITIATE_PATH)));

        wireMock.resetAll();
        stubPost(PaynowClient.INITIATE_PATH, 200, "<html><body>Request Rejected</body></html>");
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PaynowClient.INITIATE_PATH)));

        PaynowClient dead = newClient("http://127.0.0.1:1");
        assertThatThrownBy(() -> dead.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(PaynowApiTransientException.class);
    }

    @Test
    @DisplayName("NotSent only when nothing left us: a refused connection or an open breaker; a 5xx after the write never is")
    void notSent_onlyWhenNothingWasSent() {
        PaynowClient dead = newClient("http://127.0.0.1:1");
        assertThatThrownBy(() -> dead.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .as("connection refused").isInstanceOf(PaynowNotSentException.class);

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        PaynowClient guarded = newClient(wireMock.baseUrl(), breakers);
        breakers.circuitBreaker(PaynowClient.RESILIENCE_INSTANCE_NAME).transitionToOpenState();
        assertThatThrownBy(() -> guarded.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .as("breaker open").isInstanceOf(PaynowNotSentException.class);
        assertThatThrownBy(() -> guarded.poll("USD", pollUrl())).isInstanceOf(PaynowNotSentException.class);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(PaynowClient.INITIATE_PATH)));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/Interface/CheckPayment/")));

        stubPost(PaynowClient.INITIATE_PATH, 503, "Service Unavailable");
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .as("the request was written: ambiguous, never 'not sent'")
                .isInstanceOf(PaynowApiTransientException.class)
                .isNotInstanceOf(PaynowNotSentException.class);
    }

    @Test
    @DisplayName("pollOnce reads once: a failed read is not retried (the O'mari reply must answer within 60 s)")
    void pollOnce_isNeverRetried() {
        stubPost("/Interface/CheckPayment/", 503, "Service Unavailable");
        assertThatThrownBy(() -> client.pollOnce("USD", pollUrl())).isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/Interface/CheckPayment/")));

        wireMock.resetAll();
        stubPost("/Interface/CheckPayment/", 503, "Service Unavailable");
        assertThatThrownBy(() -> client.poll("USD", pollUrl())).isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/Interface/CheckPayment/")));
    }

    @Test
    @DisplayName("guard rails: no integration for the currency, or a malformed trace, never reaches the wire")
    void guardRails() {
        assertThatThrownBy(() -> client.initiateWeb("ZWG", REFERENCE, 1250, "x", "https://w.example/r", TRACE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.initiateWeb("USD", REFERENCE, 1250, "x", "https://w.example/r", "not-a-trace"))
                .isInstanceOf(IllegalArgumentException.class);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(PaynowClient.INITIATE_PATH)));
    }

    // --- express ------------------------------------------------------------

    @Test
    @DisplayName("OneMoney express: method, phone, authemail and trace on the wire, signed")
    void express_oneMoney() {
        stubPost(PaynowClient.EXPRESS_PATH, 200, signed("status=Ok&pollurl=" + enc(pollUrl())
                + "&instructions=" + enc("Approve the payment on your phone") + "&paynowreference=9511"));

        PaynowInitiation init = client.initiateExpress("USD", REFERENCE, 1000, "Ticketize tickets", TRACE,
                "onemoney", "0711111111");

        assertThat(init.pollUrl()).isEqualTo(pollUrl());
        assertThat(init.browserUrl()).isNull();
        assertThat(init.instructions()).isEqualTo("Approve the payment on your phone");
        PaynowMessage request = sent(PaynowClient.EXPRESS_PATH);
        assertThat(request.get("method")).isEqualTo("onemoney");
        assertThat(request.get("phone")).isEqualTo("0711111111");
        assertThat(request.get("authemail")).isEqualTo("payments@example.co.zw");
        assertThat(request.get("merchanttrace")).isEqualTo(TRACE);
        assertThat(request.get("amount")).isEqualTo("10.00");
        assertThat(PaynowHash.verify(request, KEY)).isTrue();
    }

    @Test
    @DisplayName("O'mari express: the code reference and the URL to post the code to")
    void express_omari() {
        stubPost(PaynowClient.EXPRESS_PATH, 200, signed("status=Ok&pollurl=" + enc(pollUrl())
                + "&otpreference=OTP-7731&remoteotpurl=" + enc(wireMock.baseUrl() + "/interface/remoteotp/9512")));

        PaynowInitiation init = client.initiateExpress("USD", REFERENCE, 1000, "x", TRACE, "omari", "0771111111");

        assertThat(init.otpReference()).isEqualTo("OTP-7731");
        assertThat(init.remoteOtpUrl()).isEqualTo(wireMock.baseUrl() + "/interface/remoteotp/9512");
    }

    @Test
    @DisplayName("express refused at the start (test number 0774444444): Insufficient balance")
    void express_insufficientBalance() {
        stubPost(PaynowClient.EXPRESS_PATH, 200, "status=Error&error=Insufficient+balance");

        assertThatThrownBy(() -> client.initiateExpress("USD", REFERENCE, 1000, "x", TRACE, "onemoney", "0774444444"))
                .isInstanceOf(PaynowApiException.class)
                .satisfies(e -> assertThat(((PaynowApiException) e).paynowError()).isEqualTo("Insufficient balance"));
    }

    // --- poll / trace -------------------------------------------------------

    @Test
    @DisplayName("poll: an empty POST to the poll URL; the documented status update reads as PAID")
    void poll_paid() {
        stubPost("/Interface/CheckPayment/", 200, signed("reference=" + REFERENCE
                + "&paynowreference=123456&amount=12.50&status=Awaiting+Delivery&pollurl=" + enc(pollUrl())));

        PaynowStatus status = client.poll("USD", pollUrl());

        assertThat(status.outcome()).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(status.rawStatus()).isEqualTo("Awaiting Delivery");
        assertThat(status.amount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(status.reference()).isEqualTo(REFERENCE);
        assertThat(status.paynowReference()).isEqualTo("123456");
        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo("/Interface/CheckPayment/")));
        assertThat(requests.get(0).getBodyAsString()).isEmpty();
    }

    @Test
    @DisplayName("poll: a status whose hash does not verify is retried, then no answer")
    void poll_badHash() {
        stubPost("/Interface/CheckPayment/", 200, "reference=R&amount=12.50&status=Paid&hash=00");

        assertThatThrownBy(() -> client.poll("USD", pollUrl())).isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/Interface/CheckPayment/")));
    }

    @Test
    @DisplayName("poll: a stored URL off Paynow's hosts is never called")
    void poll_offHost() {
        assertThatThrownBy(() -> client.poll("USD", "https://evil.example/poll"))
                .isInstanceOf(PaynowApiTransientException.class);
        assertThatThrownBy(() -> client.poll("USD", "https://user@" + wireMock.baseUrl().substring(7) + "/x"))
                .isInstanceOf(PaynowApiTransientException.class);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("trace: signed request; NotFound reads as NOT_FOUND, a Paid status as PAID with its poll URL")
    void trace_shapes() {
        stubPost(PaynowClient.TRACE_PATH, 200, signed("status=NotFound"));
        assertThat(client.trace("USD", TRACE).outcome()).isEqualTo(PaynowStatus.Outcome.NOT_FOUND);
        PaynowMessage request = sent(PaynowClient.TRACE_PATH);
        assertThat(request.get("id")).isEqualTo("1201");
        assertThat(request.get("merchanttrace")).isEqualTo(TRACE);
        assertThat(request.get("status")).isEqualTo("Message");
        assertThat(PaynowHash.verify(request, KEY)).isTrue();

        wireMock.resetAll();
        stubPost(PaynowClient.TRACE_PATH, 200, signed("reference=" + REFERENCE
                + "&paynowreference=123456&amount=12.50&status=Paid&pollurl=" + enc(pollUrl())));
        PaynowStatus paid = client.trace("USD", TRACE);
        assertThat(paid.outcome()).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(paid.pollUrl()).isEqualTo(pollUrl());
    }

    @Test
    @DisplayName("trace Error is no answer (Paynow: it does not mean not found)")
    void trace_error() {
        stubPost(PaynowClient.TRACE_PATH, 200, "status=Error&error=Trace+failed");

        assertThatThrownBy(() -> client.trace("USD", TRACE)).isInstanceOf(PaynowApiTransientException.class);
    }

    // --- O'mari code ----------------------------------------------------------

    @Test
    @DisplayName("O'mari code: signed, refused as Invalid OTP, and sent ONCE even on a 5xx")
    void submitOtp() {
        String otpPath = "/interface/remoteotp/9512";
        stubPost(otpPath, 200, "status=Error&error=Invalid+OTP");
        assertThatThrownBy(() -> client.submitOtp("USD", wireMock.baseUrl() + otpPath, "012345"))
                .isInstanceOf(PaynowApiException.class)
                .satisfies(e -> assertThat(((PaynowApiException) e).paynowError()).isEqualTo("Invalid OTP"));
        PaynowMessage request = sent(otpPath);
        assertThat(request.get("otp")).isEqualTo("012345");
        assertThat(PaynowHash.verify(request, KEY)).isTrue();

        wireMock.resetAll();
        stubPost(otpPath, 502, "Bad Gateway");
        assertThatThrownBy(() -> client.submitOtp("USD", wireMock.baseUrl() + otpPath, "012345"))
                .isInstanceOf(PaynowApiTransientException.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(otpPath)));

        wireMock.resetAll();
        stubPost(otpPath, 200, signed("reference=" + REFERENCE + "&paynowreference=9512&amount=10.00&status=Paid"
                + "&pollurl=" + enc(pollUrl())));
        assertThat(client.submitOtp("USD", wireMock.baseUrl() + otpPath, "012345").outcome())
                .isEqualTo(PaynowStatus.Outcome.PAID);
    }

    // --- result-URL updates -----------------------------------------------------

    @Test
    @DisplayName("a result-URL update counts only when signed with the payment's own integration")
    void verifiedUpdate() {
        String body = signed("reference=" + REFERENCE + "&paynowreference=1&amount=12.50&status=Paid&pollurl="
                + enc(pollUrl()));

        assertThat(client.verifiedUpdate("USD", PaynowMessage.parse(body))).get()
                .extracting(PaynowStatus::outcome).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(client.verifiedUpdate("ZWG", PaynowMessage.parse(body))).isEmpty();
        assertThat(client.verifiedUpdate("USD", PaynowMessage.parse(body.replace("12.50", "1.00")))).isEmpty();
    }

    @Test
    @DisplayName("the status words Paynow documents, plus test mode's Failed")
    void outcomes() {
        assertThat(PaynowStatus.outcomeOf("Paid")).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(PaynowStatus.outcomeOf("Awaiting Delivery")).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(PaynowStatus.outcomeOf("Delivered")).isEqualTo(PaynowStatus.Outcome.PAID);
        assertThat(PaynowStatus.outcomeOf("Created")).isEqualTo(PaynowStatus.Outcome.OPEN);
        assertThat(PaynowStatus.outcomeOf("Sent")).isEqualTo(PaynowStatus.Outcome.OPEN);
        assertThat(PaynowStatus.outcomeOf("Cancelled")).isEqualTo(PaynowStatus.Outcome.CANCELLED);
        assertThat(PaynowStatus.outcomeOf("Failed")).isEqualTo(PaynowStatus.Outcome.CANCELLED);
        assertThat(PaynowStatus.outcomeOf("Refunded")).isEqualTo(PaynowStatus.Outcome.REVERSED);
        assertThat(PaynowStatus.outcomeOf("Disputed")).isEqualTo(PaynowStatus.Outcome.REVERSED);
        assertThat(PaynowStatus.outcomeOf("Something new")).isEqualTo(PaynowStatus.Outcome.UNKNOWN);
    }
}
