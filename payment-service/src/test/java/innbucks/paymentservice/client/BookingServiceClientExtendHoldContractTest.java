package innbucks.paymentservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.config.PooledHttpClient;
import innbucks.paymentservice.config.PooledHttpClientProperties;
import innbucks.paymentservice.order.BookingOrderGateway;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The extend-hold wire contract, called before every payment instrument is
 * minted. One case per response shape booking-service answers with: the 200,
 * the plain 409 (not PENDING, past a cap), the 409s that name the event's
 * sales (event-service V22: {@code data.errorCode} SALES_CLOSED / EVENT_ENDED,
 * which the customer must read in words, not as "your booking has expired"),
 * a 404, and a booking-service that cannot be reached.
 */
class BookingServiceClientExtendHoldContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String TOKEN = "test-internal-token";
    private static final UUID BOOKING = UUID.fromString("a3b9c1d2-1234-5678-9abc-def012345678");
    private static final String PATH = "/bookings/internal/" + BOOKING + "/extend-hold";
    private static final Instant UNTIL = Instant.parse("2026-12-05T15:45:00Z");

    // booking-service's BookingController examples, verbatim.
    private static final String SALES_CLOSED = """
            { "code": "409 CONFLICT", "message": "Ticket sales for this event have closed.", "data": { "errorCode": "SALES_CLOSED", "salesEndDateTime": "2026-12-05T16:00:00Z" } }
            """;
    private static final String EVENT_ENDED = """
            { "code": "409 CONFLICT", "message": "This event has ended, so tickets are no longer on sale.", "data": { "errorCode": "EVENT_ENDED" } }
            """;
    private static final String NOT_EXTENDABLE = """
            { "code": "409 CONFLICT", "message": "Your seat reservation has expired. Please start a new booking and complete payment within 5 minutes.", "data": null }
            """;
    private static final String NOT_FOUND = """
            { "code": "404 NOT_FOUND", "message": "Booking not found", "data": null }
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
        return client("http://localhost:" + wireMock.port());
    }

    private static BookingServiceClient client(String baseUrl) {
        return new BookingServiceClient(RestClient.builder(), baseUrl, 1000, 2000, TOKEN, new ObjectMapper(), POOL);
    }

    private static BookingOrderGateway gateway(BookingServiceClient client) {
        return new BookingOrderGateway(client, mock(EventServiceClient.class), "USD");
    }

    private static void answer(int status, String body) {
        wireMock.stubFor(patch(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void ok_sendsTheDeadlineInUtc_withTheInternalToken() {
        wireMock.stubFor(patch(urlEqualTo(PATH)).willReturn(okJson("""
                {"code":"200 OK","message":"Seat hold extended","data":{"id":"a3b9c1d2-1234-5678-9abc-def012345678"}}
                """)));

        client().extendHold(BOOKING, UNTIL);

        wireMock.verify(patchRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.holdUntil", equalTo("2026-12-05T15:45:00"))));
    }

    @Test
    void salesClosed_carriesItsErrorCode_andTheGatewaySaysSoInWords() {
        answer(409, SALES_CLOSED);

        assertThatThrownBy(() -> client().extendHold(BOOKING, UNTIL))
                .isInstanceOfSatisfying(BookingServiceClient.BookingConfirmationException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("SALES_CLOSED");
                    assertThat(e.getMessage()).isEqualTo("Ticket sales for this event have closed.");
                });
        assertThatThrownBy(() -> gateway(client()).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Ticket sales for this event have closed.");
                });
    }

    @Test
    void eventEnded_carriesItsErrorCode_andTheGatewaySaysSoInWords() {
        answer(409, EVENT_ENDED);

        assertThatThrownBy(() -> gateway(client()).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("This event has ended, so tickets are no longer on sale.");
                });
    }

    @Test
    void aPlain409_hasNoErrorCode_andStillReadsAsAnExpiredBooking() {
        answer(409, NOT_EXTENDABLE);

        assertThatThrownBy(() -> client().extendHold(BOOKING, UNTIL))
                .isInstanceOfSatisfying(BookingServiceClient.BookingConfirmationException.class,
                        e -> assertThat(e.getErrorCode()).isNull());
        assertThatThrownBy(() -> gateway(client()).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getMessage())
                            .isEqualTo("Your booking has expired — please create a new booking and try again");
                });
    }

    @Test
    void aSalesClosedCodeOnAnythingButA409_isNotReadAsClosedSales() {
        // Only booking-service's 409 carries that meaning; a stray code on another status changes nothing.
        answer(404, NOT_FOUND.replace("\"data\": null", "\"data\": { \"errorCode\": \"SALES_CLOSED\" }"));

        assertThatThrownBy(() -> gateway(client()).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> assertThat(e.getMessage())
                        .isEqualTo("Your booking has expired — please create a new booking and try again"));
    }

    @Test
    void notFound_readsAsAnExpiredBooking() {
        answer(404, NOT_FOUND);

        assertThatThrownBy(() -> gateway(client()).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getMessage())
                            .isEqualTo("Your booking has expired — please create a new booking and try again");
                });
    }

    @Test
    void unreachable_is503_withNoErrorCode() {
        // A port nothing listens on; the shared WireMock is never stopped.
        BookingServiceClient closed = client("http://localhost:1");

        assertThatThrownBy(() -> closed.extendHold(BOOKING, UNTIL))
                .isInstanceOfSatisfying(BookingServiceClient.BookingConfirmationException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getErrorCode()).isNull();
                });
        assertThatThrownBy(() -> gateway(closed).extendHold(BOOKING.toString(), UNTIL))
                .isInstanceOfSatisfying(InvalidPaymentRequestException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(503));
    }
}
