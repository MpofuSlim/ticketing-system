package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.client.MarketplaceOrderClient.MarketplaceOrderException;
import innbucks.paymentservice.client.MarketplaceOrderClient.OrderView;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.MarketplaceOrderGateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link MarketplaceOrderClient} against marketplace-
 * service's internal order surface ({@code /marketplace/internal/orders/*}) —
 * one case per response shape the marketplace's {@code InternalOrderController}
 * documents (see market-place {@code docs/fleet-wiring.md}): the ApiResult
 * envelope on the happy reads, the {@code amount_mismatch} 422, the
 * {@code order_not_confirmable} / {@code order_already_paid} 409s, the
 * token-or-unknown-ref 404, and a connect-refused fault. Outbound wire
 * contract pinned too: {@code X-Internal-Token} on every call, the
 * {@code {paymentRef, amountCents}} confirm body, and the encoded-path caveat
 * for refs carrying reserved characters. The {@link MarketplaceOrderGateway}
 * confirm-outcome mapping (200 → CONFIRMED, 409/422 → REJECTED,
 * connect-refused → UNREACHABLE) is asserted through the same wire stubs.
 *
 * <p>Pure JUnit + standalone WireMock, no {@code @SpringBootTest}, per the
 * house contract-test convention.
 */
class MarketplaceOrderClientContractTest {

    private static final String TOKEN = "test-internal-token";
    private static final String REF = "MKT-4F9A1C22B7D3";
    private static final String BASE = "/marketplace/internal/orders/";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    /** Production shape (LoadBalanced builder swapped for a plain one), pointed at WireMock. */
    private static MarketplaceOrderClient client(int port) {
        return new MarketplaceOrderClient(
                RestClient.builder(),
                "http://localhost:" + port,
                1000, 2000,
                TOKEN,
                new ObjectMapper());
    }

    /** The gateway over the same client — for the ConfirmOutcome-mapping cases. */
    private static MarketplaceOrderGateway gateway(int port) {
        return new MarketplaceOrderGateway(client(port), Duration.ofMinutes(10));
    }

    // ---- fetch ------------------------------------------------------------

    @Test
    @DisplayName("GET order: parses the ApiResult envelope's data and sends X-Internal-Token")
    void getOrder_parsesTheDocumentedEnvelope() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(okJson("""
                {
                  "code": "OK",
                  "message": "Success",
                  "data": {
                    "orderRef": "MKT-4F9A1C22B7D3",
                    "status": "PENDING_PAYMENT",
                    "totalCents": 3550,
                    "currency": "USD",
                    "buyerMsisdn": "+263771234567",
                    "expiresAt": "2026-08-05T10:45:00Z"
                  }
                }
                """)));

        OrderView order = client(wireMock.port()).getOrder(REF);

        assertThat(order.orderRef()).isEqualTo(REF);
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.totalCents()).isEqualTo(3550L);
        assertThat(order.currency()).isEqualTo("USD");
        assertThat(order.buyerMsisdn()).isEqualTo("+263771234567");
        assertThat(order.expiresAt()).isEqualTo(Instant.parse("2026-08-05T10:45:00Z"));
        wireMock.verify(getRequestedFor(urlEqualTo(BASE + REF))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("GET order 404 (unknown ref OR rejected token — indistinguishable by design): status + envelope code relayed")
    void getOrder_404_relaysStatusAndCode() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"code":"order_not_found","message":"Order not found"}
                        """)));

        assertThatThrownBy(() -> client(wireMock.port()).getOrder(REF))
                .isInstanceOfSatisfying(MarketplaceOrderException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("order_not_found");
                    assertThat(e.getMessage()).isEqualTo("Order not found");
                });
    }

    @Test
    @DisplayName("encoded-path caveat: a ref with reserved chars hits the wire percent-encoded (%3A)")
    void getOrder_refWithReservedChars_isPercentEncodedOnTheWire() {
        // Marketplace refs are MKT-<hex> today; this pins the client's
        // encoding behaviour so stubs/proxy rules are written against the
        // ENCODED form if refs ever grow reserved characters.
        wireMock.stubFor(get(urlEqualTo(BASE + "MKT%3AX1")).willReturn(okJson("""
                {"code":"OK","message":"Success","data":{
                  "orderRef":"MKT:X1","status":"PENDING_PAYMENT","totalCents":100,
                  "currency":"USD","buyerMsisdn":"+263771234567",
                  "expiresAt":"2026-08-05T10:45:00Z"}}
                """)));

        OrderView order = client(wireMock.port()).getOrder("MKT:X1");

        assertThat(order.totalCents()).isEqualTo(100L);
        wireMock.verify(getRequestedFor(urlEqualTo(BASE + "MKT%3AX1")));
    }

    // ---- extend-expiry ----------------------------------------------------

    @Test
    @DisplayName("extend-expiry: PATCH ?minutes=N with X-Internal-Token")
    void extendExpiry_sendsMinutesAndToken() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/extend-expiry?minutes=13"))
                .willReturn(okJson("""
                        {"code":"OK","message":"Order expiry extended","data":{
                          "orderRef":"MKT-4F9A1C22B7D3","status":"PENDING_PAYMENT","totalCents":3550,
                          "currency":"USD","buyerMsisdn":"+263771234567",
                          "expiresAt":"2026-08-05T10:58:00Z"}}
                        """)));

        client(wireMock.port()).extendExpiry(REF, 13);

        wireMock.verify(patchRequestedFor(urlEqualTo(BASE + REF + "/extend-expiry?minutes=13"))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("extend-expiry 409 order_not_extendable: status + code relayed")
    void extendExpiry_409_relaysStatusAndCode() {
        wireMock.stubFor(patch(urlMatching(BASE + REF + "/extend-expiry.*"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"order_not_extendable","message":"Order MKT-4F9A1C22B7D3 is not awaiting payment"}
                                """)));

        assertThatThrownBy(() -> client(wireMock.port()).extendExpiry(REF, 13))
                .isInstanceOfSatisfying(MarketplaceOrderException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("order_not_extendable");
                });
    }

    @Test
    @DisplayName("guard rail: out-of-range minutes never reach the network (marketplace contract is 1..60)")
    void extendExpiry_outOfRangeMinutes_neverHitsTheNetwork() {
        assertThatThrownBy(() -> client(wireMock.port()).extendExpiry(REF, 0))
                .isInstanceOfSatisfying(MarketplaceOrderException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(400));
        assertThatThrownBy(() -> client(wireMock.port()).extendExpiry(REF, 61))
                .isInstanceOfSatisfying(MarketplaceOrderException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(400));

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    // ---- confirm-payment --------------------------------------------------

    @Test
    @DisplayName("confirm 200: outbound body is {paymentRef, amountCents} + token; gateway maps to CONFIRMED")
    void confirm_200_postsDocumentedBody_andMapsConfirmed() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(okJson("""
                        {"code":"OK","message":"Payment confirmed","data":{
                          "orderRef":"MKT-4F9A1C22B7D3","status":"PAID","totalCents":3550,
                          "currency":"USD","buyerMsisdn":"+263771234567",
                          "expiresAt":"2026-08-05T10:45:00Z"}}
                        """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-MKT-4F3A2B1C0D9E", 3550L);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.CONFIRMED);
        wireMock.verify(patchRequestedFor(urlEqualTo(BASE + REF + "/confirm-payment"))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.paymentRef", equalTo("TKZ-MKT-4F3A2B1C0D9E")))
                .withRequestBody(matchingJsonPath("$.amountCents", equalTo("3550"))));
    }

    @Test
    @DisplayName("confirm 422 amount_mismatch (the 100x guard's confirm leg): gateway maps to REJECTED, never success")
    void confirm_422AmountMismatch_mapsRejected() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"amount_mismatch","message":"Paid amount 3500 does not match order total 3550"}
                                """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-MKT-4F3A2B1C0D9E", 3500L);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.REJECTED);
        assertThat(outcome.reason()).contains("amount_mismatch");
    }

    @Test
    @DisplayName("confirm 409 order_not_confirmable (CANCELLED/EXPIRED order): gateway maps to REJECTED")
    void confirm_409NotConfirmable_mapsRejected() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"order_not_confirmable","message":"Order MKT-4F9A1C22B7D3 is EXPIRED and cannot be confirmed"}
                                """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-MKT-4F3A2B1C0D9E", 3550L);

        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.REJECTED);
        assertThat(outcome.reason()).contains("order_not_confirmable");
    }

    @Test
    @DisplayName("confirm 409 order_already_paid (different paymentRef): gateway maps to REJECTED — operator territory")
    void confirm_409AlreadyPaidUnderDifferentRef_mapsRejected() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"order_already_paid","message":"Order MKT-4F9A1C22B7D3 is already paid with a different payment reference"}
                                """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-MKT-DIFFERENT", 3550L);

        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.REJECTED);
        assertThat(outcome.reason()).contains("order_already_paid");
    }

    @Test
    @DisplayName("connect-refused: client answers 503 marketplace_unreachable; gateway maps confirm to UNREACHABLE")
    void connectRefused_mapsUnreachable() {
        // A separate client at a known-closed port — never stop/restart the
        // shared WireMock (a second start gets a different dynamic port and
        // breaks the other tests).
        int closedPort = closedPort();

        assertThatThrownBy(() -> client(closedPort).getOrder(REF))
                .isInstanceOfSatisfying(MarketplaceOrderException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("marketplace_unreachable");
                });

        ConfirmOutcome outcome = gateway(closedPort).confirm(REF, "TKZ-MKT-4F3A2B1C0D9E", 3550L);
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.UNREACHABLE);
        assertThat(outcome.succeeded()).isFalse();
    }

    // ---- guard rails ------------------------------------------------------

    @Test
    @DisplayName("guard rails: blank ref / blank paymentRef / non-positive cents never hit the network")
    void guardRails_neverHitTheNetwork() {
        MarketplaceOrderClient c = client(wireMock.port());

        assertThatThrownBy(() -> c.getOrder(" "))
                .isInstanceOf(MarketplaceOrderException.class);
        assertThatThrownBy(() -> c.confirmPayment(REF, " ", 3550L))
                .isInstanceOfSatisfying(MarketplaceOrderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("payment_ref_required"));
        assertThatThrownBy(() -> c.confirmPayment(REF, "TKZ-MKT-4F3A2B1C0D9E", 0L))
                .isInstanceOfSatisfying(MarketplaceOrderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("invalid_amount"));
        assertThatThrownBy(() -> c.confirmPayment(REF, "TKZ-MKT-4F3A2B1C0D9E", -100L))
                .isInstanceOf(MarketplaceOrderException.class);

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    private static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
