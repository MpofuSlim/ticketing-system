package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link InnbucksApiClient} against the InnBucks Merchant
 * API (spec pinned at {@code docs/api/innbucks-merchant-api.md}, distilled
 * from the official v1.0.9 PDF). The stubs below mirror the doc's sample
 * bodies; when staging responses diverge, update the doc, these stubs and
 * the classifier together — a contract drift fails the build, not production.
 *
 * <p>Pure JUnit + WireMock, no Spring context. Retry registry uses a real
 * 2-attempt config so the query-only retry policy is observable: the
 * status QUERY retries on 5xx, code GENERATION never does.
 */
class InnbucksApiClientContractTest {

    private static WireMockServer wireMock;

    private static InnbucksApiClient newClient(String baseUrl) {
        InnbucksApiProperties props = new InnbucksApiProperties();
        props.setBaseUrl(baseUrl);
        props.setApiKey("test-api-key");
        props.setUsername("merchant-user");
        props.setPassword("merchant-pass");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);
        props.setTokenTtl(Duration.ofMinutes(5));
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(InnbucksApiTransientException.class)
                .build());
        return new InnbucksApiClient(props, new ObjectMapper(), retries, CircuitBreakerRegistry.ofDefaults());
    }

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private void stubLogin() {
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"jwt-token-1\",\"responseCode\":\"00\"}")));
    }

    /** The doc's sample generation response (responseCode is a NUMBER here). */
    private static final String GENERATED_OK = """
            {
              "stan": "414107",
              "authNumber": "1616800",
              "processedDateTime": "2026-06-11 13:30:53.515",
              "responseCode": 0,
              "responseMsg": "Approved or completed successfully",
              "code": "701285660",
              "qrCode": "aGVsbG8=",
              "amount": "5000",
              "description": "InnBucks ticket booking a3b9c1d2"
            }
            """;

    @Test
    @DisplayName("login: posts X-Api-Key + credentials, caches the token across calls")
    void login_sendsApiKeyAndCredentials_andCachesToken() {
        InnbucksApiClient fresh = newClient("http://localhost:" + wireMock.port());
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATED_OK)));

        fresh.generatePaymentCode("TKT-PMT-x", "n1", 5000);
        fresh.generatePaymentCode("TKT-PMT-y", "n2", 5000);

        // Exactly ONE login despite two generations — the token is cached.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/auth/third-party"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("merchant-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("merchant-pass"))));
    }

    @Test
    @DisplayName("generate: wire shape — type=PAYMENT, amount in CENTS as a number, our reference")
    void generate_postsDocumentedShape_andParsesHandles() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATED_OK)));

        CodeGenerationResult result = newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "InnBucks ticket booking a3b9c1d2", 5000);

        assertThat(result.approved()).isTrue();
        assertThat(result.code()).isEqualTo("701285660");
        assertThat(result.authNumber()).isEqualTo("1616800");
        assertThat(result.qrCodeBase64()).isEqualTo("aGVsbG8=");
        assertThat(result.stan()).isEqualTo("414107");
        assertThat(result.amountEchoCents()).isEqualTo(5000L);
        wireMock.verify(postRequestedFor(urlEqualTo("/api/code/generate"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-1"))
                // Cents as an integral JSON number — the 100x guard starts
                // with never serialising a decimal here.
                .withRequestBody(matchingJsonPath("$.amount", equalTo("5000")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("PAYMENT")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("TKT-PMT-x")))
                .withRequestBody(matchingJsonPath("$.narration", equalTo("InnBucks ticket booking a3b9c1d2"))));
    }

    @Test
    @DisplayName("generate: responseCode 0 but missing code/authNumber → NOT approved (no guessed success)")
    void generate_successWithoutHandles_isNotApproved() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":0,\"responseMsg\":\"ok\"}")));

        CodeGenerationResult result = newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000);

        assertThat(result.approved()).isFalse();
    }

    @Test
    @DisplayName("generate: non-zero responseCode in a 2xx → refused with the upstream reason")
    void generate_nonZeroResponseCode_isRefused() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":96,\"responseMsg\":\"Request failed, please try again later\"}")));

        CodeGenerationResult result = newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000);

        assertThat(result.approved()).isFalse();
        assertThat(result.responseCode()).isEqualTo("96");
        assertThat(result.responseMsg()).contains("Request failed");
    }

    @Test
    @DisplayName("generate: 4xx carrying the documented envelope → refused result, not an exception")
    void generate_4xxWithEnvelope_isRefusedResult() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":13,\"responseMsg\":\"Invalid amount\"}")));

        CodeGenerationResult result = newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000);

        assertThat(result.approved()).isFalse();
        assertThat(result.responseMsg()).isEqualTo("Invalid amount");
    }

    @Test
    @DisplayName("generate: bare 4xx without the envelope → permanent InnbucksApiException")
    void generate_bare4xx_throwsPermanent() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(400).withBody("Bad Request")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000))
                .isInstanceOf(InnbucksApiException.class)
                .isNotInstanceOf(InnbucksApiTransientException.class);
    }

    @Test
    @DisplayName("generate: 5xx → transient AND hits the wire exactly once (never retried)")
    void generate_5xx_isTransient_andNotRetried() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000))
                .isInstanceOf(InnbucksApiTransientException.class);

        // A retry could mint a SECOND live code — the generation call must
        // hit the wire exactly ONCE even though the registry allows 2 attempts.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/code/generate")));
    }

    @Test
    @DisplayName("generate: 401 → one token refresh + replay, then success")
    void generate_401_refreshesTokenOnce_andReplays() {
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .inScenario("relogin").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"stale-token\"}"))
                .willSetStateTo("second-login"));
        wireMock.stubFor(post(urlEqualTo("/auth/third-party"))
                .inScenario("relogin").whenScenarioStateIs("second-login")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"fresh-token\"}")));
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .withHeader("Authorization", equalTo("Bearer stale-token"))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(post(urlEqualTo("/api/code/generate"))
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATED_OK)));

        CodeGenerationResult result = newClient("http://localhost:" + wireMock.port())
                .generatePaymentCode("TKT-PMT-x", "n", 5000);

        assertThat(result.approved()).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/auth/third-party")));
    }

    @Test
    @DisplayName("query: wire shape — originalReference = the authNumber; Paid classifies as PAID")
    void query_postsOriginalReference_andClassifiesPaid() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/query/originalReference"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "stan": "1655116056",
                                  "authNumber": "1616800",
                                  "responseCode": 0,
                                  "responseMsg": "Approved or completed successfully",
                                  "code": "701285660",
                                  "amount": "5000",
                                  "status": "Paid",
                                  "timeToLive": "0sec"
                                }
                                """)));

        CodeStatusResult result = newClient("http://localhost:" + wireMock.port())
                .queryCodeStatus("1616800");

        assertThat(result.status()).isEqualTo(CodeStatusResult.Status.PAID);
        assertThat(result.isPaid()).isTrue();
        wireMock.verify(postRequestedFor(urlEqualTo("/api/code/query/originalReference"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-1"))
                .withRequestBody(matchingJsonPath("$.originalReference", equalTo("1616800")))
                .withRequestBody(matchingJsonPath("$.reference", matching("Q-.+"))));
    }

    @Test
    @DisplayName("query: full status vocabulary — Claimed=paid, 'Timed Out', Expired, New, garbage=UNKNOWN")
    void query_classifiesTheDocumentedVocabulary() {
        stubLogin();
        InnbucksApiClient client = newClient("http://localhost:" + wireMock.port());

        for (var expected : java.util.Map.of(
                "Claimed", CodeStatusResult.Status.CLAIMED,
                "Timed Out", CodeStatusResult.Status.TIMED_OUT,
                "Expired", CodeStatusResult.Status.EXPIRED,
                "New", CodeStatusResult.Status.NEW,
                "SomethingNovel", CodeStatusResult.Status.UNKNOWN).entrySet()) {
            wireMock.stubFor(post(urlEqualTo("/api/code/query/originalReference"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"responseCode\":0,\"status\":\"" + expected.getKey() + "\"}")));

            CodeStatusResult result = client.queryCodeStatus("1616800");

            assertThat(result.status())
                    .as("status string '%s'", expected.getKey())
                    .isEqualTo(expected.getValue());
        }
        assertThat(CodeStatusResult.Status.CLAIMED).satisfies(s ->
                assertThat(new CodeStatusResult(s, "Claimed", null).isPaid())
                        .as("doc: Claimed = finalised by the customer")
                        .isTrue());
    }

    @Test
    @DisplayName("query: non-zero responseCode → ERROR (row left alone by the poller)")
    void query_nonZeroResponseCode_isError() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/query/originalReference"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":25,\"responseMsg\":\"Unable to locate original request\"}")));

        CodeStatusResult result = newClient("http://localhost:" + wireMock.port())
                .queryCodeStatus("does-not-exist");

        assertThat(result.status()).isEqualTo(CodeStatusResult.Status.ERROR);
        assertThat(result.responseMsg()).contains("Unable to locate");
    }

    @Test
    @DisplayName("query: 5xx IS retried (read-only) — both attempts hit the wire")
    void query_5xx_isRetried() {
        stubLogin();
        wireMock.stubFor(post(urlEqualTo("/api/code/query/originalReference"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .queryCodeStatus("1616800"))
                .isInstanceOf(InnbucksApiTransientException.class);

        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/code/query/originalReference")));
    }

    @Test
    @DisplayName("unconfigured credentials: refuses before any HTTP call")
    void unconfigured_refusesWithoutNetwork() {
        InnbucksApiProperties blank = new InnbucksApiProperties();
        InnbucksApiClient unconfigured = new InnbucksApiClient(blank, new ObjectMapper(),
                RetryRegistry.ofDefaults(), CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> unconfigured.generatePaymentCode("TKT-PMT-x", "n", 5000))
                .isInstanceOf(InnbucksApiException.class)
                .hasMessageContaining("not configured");
        assertThat(unconfigured.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("connect refused: transient (separate dead-port client; shared WireMock untouched)")
    void connectRefused_isTransient() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        InnbucksApiClient dead = newClient("http://localhost:" + closedPort);

        assertThatThrownBy(() -> dead.generatePaymentCode("TKT-PMT-x", "n", 5000))
                .isInstanceOf(InnbucksApiTransientException.class);
    }

    // ---- mini statement (settlement reconciliation) -------------------------

    /** The doc's sample mini-statement (entry array is named "code"). */
    private static final String MINI_STATEMENT_OK = """
            {
              "stan": "1655116056",
              "authNumber": "7880",
              "responseCode": 0,
              "responseMsg": "Approved or completed successfully",
              "code": [
                {
                  "amount": "4000",
                  "code": "701848897",
                  "codeType": null,
                  "createDate": "2026-06-11 12:11:23",
                  "closedDate": "2026-06-11 12:14:08",
                  "state": "Claimed"
                },
                {
                  "amount": "4000",
                  "code": "701977985",
                  "codeType": null,
                  "createDate": "2026-06-11 12:07:30",
                  "closedDate": null,
                  "state": "Pending"
                }
              ]
            }
            """;

    @Test
    @DisplayName("mini statement: GET by accountId; parses entries with cents, state and createDate")
    void miniStatement_parsesEntries() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/api/code/2009200566693/miniStatement"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MINI_STATEMENT_OK)));

        java.util.List<CodeStatementEntry> entries =
                newClient("http://localhost:" + wireMock.port()).fetchCodeMiniStatement("2009200566693");

        assertThat(entries).hasSize(2);
        CodeStatementEntry claimed = entries.get(0);
        assertThat(claimed.code()).isEqualTo("701848897");
        assertThat(claimed.amountCents()).isEqualTo(4000L);
        assertThat(claimed.isFinalised()).as("Claimed = finalised per the doc").isTrue();
        assertThat(claimed.createdAt()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 11, 12, 11, 23));
        assertThat(entries.get(1).isFinalised()).as("Pending is still in flight").isFalse();
        wireMock.verify(getRequestedFor(urlEqualTo("/api/code/2009200566693/miniStatement"))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-1")));
    }

    @Test
    @DisplayName("mini statement: responseCode 0 with no entry array → empty statement, not an error")
    void miniStatement_missingArray_isEmpty() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/api/code/2009200566693/miniStatement"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":0,\"responseMsg\":\"ok\"}")));

        assertThat(newClient("http://localhost:" + wireMock.port())
                .fetchCodeMiniStatement("2009200566693")).isEmpty();
    }

    @Test
    @DisplayName("mini statement: non-zero responseCode → loud InnbucksApiException (recon must FAIL, not conclude empty)")
    void miniStatement_nonZeroResponseCode_throws() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/api/code/2009200566693/miniStatement"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseCode\":96,\"responseMsg\":\"Request failed\"}")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .fetchCodeMiniStatement("2009200566693"))
                .isInstanceOf(InnbucksApiException.class)
                .hasMessageContaining("96");
    }

    @Test
    @DisplayName("mini statement: 5xx IS retried (read-only) — both attempts hit the wire")
    void miniStatement_5xx_isRetried() {
        stubLogin();
        wireMock.stubFor(get(urlEqualTo("/api/code/2009200566693/miniStatement"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        assertThatThrownBy(() -> newClient("http://localhost:" + wireMock.port())
                .fetchCodeMiniStatement("2009200566693"))
                .isInstanceOf(InnbucksApiTransientException.class);

        wireMock.verify(2, getRequestedFor(urlEqualTo("/api/code/2009200566693/miniStatement")));
    }
}
