package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.config.PooledHttpClient;
import innbucks.paymentservice.config.PooledHttpClientProperties;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.BookingOrderGateway;
import innbucks.paymentservice.order.ConfirmOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The booking confirm's wire contract: payment-service tells booking-service
 * which rail paid ({@code {"paymentRail": "ECOCASH"}}), so the organizer's
 * guest list and CSV show the payment method (booking-service V29). Without a
 * rail the call stays bodyless, the historical contract.
 */
class BookingServiceClientConfirmContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String TOKEN = "test-internal-token";
    private static final UUID BOOKING = UUID.fromString("a3b9c1d2-1234-5678-9abc-def012345678");
    private static final String PATH = "/bookings/internal/" + BOOKING + "/confirm";
    private static final String CONFIRMED = """
            {"code":"200 OK","message":"Booking confirmed successfully",
             "data":{"id":"a3b9c1d2-1234-5678-9abc-def012345678","confirmationNumber":"INN-20261030-AB12CD",
                     "status":"CONFIRMED"}}
            """;

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

    private static BookingServiceClient client() {
        return new BookingServiceClient(RestClient.builder(), "http://localhost:" + wireMock.port(),
                1000, 2000, TOKEN, new ObjectMapper(), POOL);
    }

    @Test
    void confirm_sendsTheRailThatPaid() {
        wireMock.stubFor(patch(urlEqualTo(PATH)).willReturn(okJson(CONFIRMED)));

        Map<String, Object> data = client().confirmBooking(BOOKING, "ECOCASH");

        assertThat(data).containsEntry("confirmationNumber", "INN-20261030-AB12CD");
        wireMock.verify(patchRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.paymentRail", equalTo("ECOCASH"))));
    }

    @Test
    void confirm_withoutARail_sendsNoBody_asBefore() {
        wireMock.stubFor(patch(urlEqualTo(PATH)).willReturn(okJson(CONFIRMED)));

        client().confirmBooking(BOOKING, null);

        wireMock.verify(patchRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(absent()));
    }

    @Test
    void theBookingGateway_passesEachRailByName() {
        wireMock.stubFor(patch(urlEqualTo(PATH)).willReturn(okJson(CONFIRMED)));
        BookingOrderGateway gateway = new BookingOrderGateway(client(), mock(EventServiceClient.class), "USD");

        for (PaymentRail rail : PaymentRail.values()) {
            ConfirmOutcome outcome = gateway.confirm(BOOKING.toString(), "TKZ-X-1", 5000L, rail);
            assertThat(outcome.succeeded()).isTrue();
            wireMock.verify(patchRequestedFor(urlEqualTo(PATH))
                    .withRequestBody(matchingJsonPath("$.paymentRail", equalTo(rail.name()))));
        }
    }
}
