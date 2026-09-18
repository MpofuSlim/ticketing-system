package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderException;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient.VoucherOrderView;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.LoyaltyVoucherOrderGateway;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
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
 * Contract test for {@link LoyaltyVoucherOrderClient} against loyalty-service's
 * internal voucher purchase-order surface ({@code /loyalty/internal/
 * voucher-orders/*}, InnRewards V47 {@code InternalVoucherOrderController}) —
 * one case per response shape that controller serves. Loyalty's internal
 * convention differs from the marketplace's in two pinned ways: responses are
 * PLAIN MAPS (no ApiResult envelope), and amounts are DECIMAL MAJOR UNITS
 * (the {@link LoyaltyVoucherOrderGateway} owns the cents conversion). The
 * bodyless 401 a bad internal token answers is pinned too. The gateway's
 * confirm-outcome mapping (200 → CONFIRMED, 409/422 → REJECTED,
 * connect-refused → UNREACHABLE) is asserted through the same wire stubs.
 *
 * <p>Pure JUnit + standalone WireMock, no {@code @SpringBootTest}, per the
 * house contract-test convention.
 */
class LoyaltyVoucherOrderClientContractTest {

    private static final String TOKEN = "test-internal-token";
    private static final String REF = "VCH-4F9A1C22B7D3";
    private static final String BASE = "/loyalty/internal/voucher-orders/";

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
    private static LoyaltyVoucherOrderClient client(int port) {
        return new LoyaltyVoucherOrderClient(
                RestClient.builder(),
                "http://localhost:" + port,
                1000, 2000,
                TOKEN,
                new ObjectMapper());
    }

    /** The gateway over the same client — for the snapshot + ConfirmOutcome cases. */
    private static LoyaltyVoucherOrderGateway gateway(int port) {
        return new LoyaltyVoucherOrderGateway(client(port), Duration.ofMinutes(10));
    }

    // ---- fetch ------------------------------------------------------------

    @Test
    @DisplayName("GET order: parses loyalty's PLAIN-MAP body (no envelope) and sends X-Internal-Token")
    void getOrder_parsesTheDocumentedPlainMap() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(okJson("""
                {
                  "orderRef": "VCH-4F9A1C22B7D3",
                  "status": "PENDING_PAYMENT",
                  "amount": 5.0000,
                  "currency": "USD",
                  "payerMsisdn": "+263782608767",
                  "expiresAt": "2026-09-17T20:15:00Z",
                  "payable": true
                }
                """)));

        VoucherOrderView order = client(wireMock.port()).getOrder(REF);

        assertThat(order.orderRef()).isEqualTo(REF);
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.amount()).isEqualByComparingTo("5.0000");
        assertThat(order.currency()).isEqualTo("USD");
        assertThat(order.payerMsisdn()).isEqualTo("+263782608767");
        assertThat(order.expiresAt()).isEqualTo(Instant.parse("2026-09-17T20:15:00Z"));
        assertThat(order.payable()).isTrue();
        wireMock.verify(getRequestedFor(urlEqualTo(BASE + REF))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("gateway snapshot: 5.0000 major units become 500 cents, tag VCH, narration carries the ref tail")
    void gatewaySnapshot_convertsMajorUnitsToCents() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(okJson("""
                {"orderRef":"VCH-4F9A1C22B7D3","status":"PENDING_PAYMENT","amount":5.0000,
                 "currency":"USD","payerMsisdn":"+263782608767",
                 "expiresAt":"2026-09-17T20:15:00Z","payable":true}
                """)));

        OrderSnapshot snapshot = gateway(wireMock.port()).fetch(REF);

        assertThat(snapshot.amountCents()).isEqualTo(500L);
        assertThat(snapshot.currency()).isEqualTo("USD");
        assertThat(snapshot.payerMsisdn()).isEqualTo("+263782608767");
        assertThat(snapshot.settlementTag()).isEqualTo("VCH");
        assertThat(snapshot.narration()).isEqualTo("Gift voucher 22B7D3");
        assertThat(snapshot.payable()).isTrue();
    }

    @Test
    @DisplayName("gateway snapshot: an EXPIRED order comes back payable=false — refused before any ledger write")
    void gatewaySnapshot_expiredOrder_isNotPayable() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(okJson("""
                {"orderRef":"VCH-4F9A1C22B7D3","status":"EXPIRED","amount":5.0000,
                 "currency":"USD","payerMsisdn":"+263782608767",
                 "expiresAt":"2026-09-17T19:00:00Z","payable":false}
                """)));

        assertThat(gateway(wireMock.port()).fetch(REF).payable()).isFalse();
    }

    @Test
    @DisplayName("GET order 404 {code,message}: status + code relayed; gateway maps to a 404 refusal")
    void getOrder_404_relaysStatusAndCode() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"code":"404 NOT_FOUND","message":"purchase order not found"}
                        """)));

        assertThatThrownBy(() -> client(wireMock.port()).getOrder(REF))
                .isInstanceOfSatisfying(VoucherOrderException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getMessage()).isEqualTo("purchase order not found");
                });
        assertThatThrownBy(() -> gateway(wireMock.port()).fetch(REF))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("bad internal token: loyalty answers a BODYLESS 401 — relayed with the status text as message")
    void getOrder_bodyless401_isRelayed() {
        wireMock.stubFor(get(urlEqualTo(BASE + REF)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).getOrder(REF))
                .isInstanceOfSatisfying(VoucherOrderException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    // ---- extend-expiry ----------------------------------------------------

    @Test
    @DisplayName("extend-expiry: PATCH ?minutes=N with X-Internal-Token")
    void extendExpiry_sendsMinutesAndToken() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/extend-expiry?minutes=13"))
                .willReturn(okJson("""
                        {"orderRef":"VCH-4F9A1C22B7D3","status":"PENDING_PAYMENT","amount":5.0000,
                         "currency":"USD","payerMsisdn":"+263782608767",
                         "expiresAt":"2026-09-17T20:28:00Z","payable":true}
                        """)));

        client(wireMock.port()).extendExpiry(REF, 13);

        wireMock.verify(patchRequestedFor(urlEqualTo(BASE + REF + "/extend-expiry?minutes=13"))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("extend-expiry 409 ORDER_NOT_EXTENDABLE: gateway refuses the payment pre-mint with a 409")
    void extendExpiry_409_gatewayRefusesPreMint() {
        wireMock.stubFor(patch(urlMatching(BASE + REF + "/extend-expiry.*"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"ORDER_NOT_EXTENDABLE","message":"This purchase order is no longer awaiting payment."}
                                """)));

        assertThatThrownBy(() -> gateway(wireMock.port()).extendHold(REF))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("guard rail: out-of-range minutes never reach the network (loyalty contract is 1..60)")
    void extendExpiry_outOfRangeMinutes_neverHitsTheNetwork() {
        assertThatThrownBy(() -> client(wireMock.port()).extendExpiry(REF, 0))
                .isInstanceOfSatisfying(VoucherOrderException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(400));
        assertThatThrownBy(() -> client(wireMock.port()).extendExpiry(REF, 61))
                .isInstanceOfSatisfying(VoucherOrderException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(400));

        wireMock.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    // ---- confirm-payment --------------------------------------------------

    @Test
    @DisplayName("confirm 200 (voucher issued loyalty-side): outbound body is {paymentRef, amountCents} + token; gateway maps to CONFIRMED")
    void confirm_200_postsDocumentedBody_andMapsConfirmed() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(okJson("""
                        {"orderRef":"VCH-4F9A1C22B7D3","status":"PAID","amount":5.0000,
                         "currency":"USD","payerMsisdn":"+263782608767",
                         "expiresAt":"2026-09-17T20:15:00Z","payable":false}
                        """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-VCH-4F3A2B1C0D9E", 500L);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.CONFIRMED);
        wireMock.verify(patchRequestedFor(urlEqualTo(BASE + REF + "/confirm-payment"))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.paymentRef", equalTo("TKZ-VCH-4F3A2B1C0D9E")))
                .withRequestBody(matchingJsonPath("$.amountCents", equalTo("500"))));
    }

    @Test
    @DisplayName("confirm 422 AMOUNT_MISMATCH (the 100x guard's confirm leg): gateway maps to REJECTED, never success")
    void confirm_422AmountMismatch_mapsRejected() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"AMOUNT_MISMATCH","message":"Paid amount 50000c does not match the order's 500c"}
                                """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-VCH-4F3A2B1C0D9E", 50000L);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.REJECTED);
        assertThat(outcome.reason()).contains("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("confirm 409 ORDER_ALREADY_PAID (different paymentRef — e.g. cash beat the rail): gateway maps to REJECTED")
    void confirm_409AlreadyPaid_mapsRejected() {
        wireMock.stubFor(patch(urlEqualTo(BASE + REF + "/confirm-payment"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"code":"ORDER_ALREADY_PAID","message":"This purchase order was already paid under a different reference."}
                                """)));

        ConfirmOutcome outcome = gateway(wireMock.port())
                .confirm(REF, "TKZ-VCH-DIFFERENT", 500L);

        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.REJECTED);
        assertThat(outcome.reason()).contains("ORDER_ALREADY_PAID");
    }

    @Test
    @DisplayName("connect-refused: client answers 503 LOYALTY_UNREACHABLE; gateway maps confirm to UNREACHABLE")
    void connectRefused_mapsUnreachable() {
        // A separate client at a known-closed port — never stop/restart the
        // shared WireMock (a second start gets a different dynamic port and
        // breaks the other tests).
        int closedPort = closedPort();

        assertThatThrownBy(() -> client(closedPort).getOrder(REF))
                .isInstanceOfSatisfying(VoucherOrderException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("LOYALTY_UNREACHABLE");
                });

        ConfirmOutcome outcome = gateway(closedPort).confirm(REF, "TKZ-VCH-4F3A2B1C0D9E", 500L);
        assertThat(outcome.result()).isEqualTo(ConfirmOutcome.Result.UNREACHABLE);
        assertThat(outcome.succeeded()).isFalse();
    }

    // ---- guard rails ------------------------------------------------------

    @Test
    @DisplayName("guard rails: blank ref / blank paymentRef / non-positive cents never hit the network")
    void guardRails_neverHitTheNetwork() {
        LoyaltyVoucherOrderClient c = client(wireMock.port());

        assertThatThrownBy(() -> c.getOrder(" "))
                .isInstanceOf(VoucherOrderException.class);
        assertThatThrownBy(() -> c.confirmPayment(REF, " ", 500L))
                .isInstanceOfSatisfying(VoucherOrderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PAYMENT_REF_REQUIRED"));
        assertThatThrownBy(() -> c.confirmPayment(REF, "TKZ-VCH-4F3A2B1C0D9E", 0L))
                .isInstanceOfSatisfying(VoucherOrderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_AMOUNT"));

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
