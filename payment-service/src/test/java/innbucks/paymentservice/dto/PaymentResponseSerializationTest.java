package innbucks.paymentservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the WIRE shape of the three-state money answer.
 *
 * <p>{@code fundsCaptured} promises clients three distinguishable values:
 * {@code true}, {@code false} and {@code null} (unknown). That contract only
 * holds if a null is serialized as an EXPLICIT null rather than omitted — an
 * absent key is indistinguishable from an older server that never sent the
 * field, which would push a client into guessing about money.
 *
 * <p>This is a live hazard rather than a theoretical one: four sibling DTOs in
 * this package (TransactionView, ShopCheckoutResponse, DepositTransferRequest,
 * WithdrawalRequest) carry {@code @JsonInclude(NON_NULL)} at class level, so
 * adding it to PaymentResponse is the natural-looking next edit.
 */
class PaymentResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("VERIFYING serializes fundsCaptured as an EXPLICIT null, never an absent key")
    void unknownIsExplicitNullOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.VERIFYING)
                .status(PaymentResponse.Status.PROCESSING)
                .build());

        assertThat(json)
                .as("the key must be present so 'unknown' is distinguishable from 'old server'")
                .contains("\"fundsCaptured\"");
        assertThat(mapper.readTree(json).get("fundsCaptured").isNull()).isTrue();
        assertThat(mapper.readTree(json).get("stage").asText()).isEqualTo("VERIFYING");
    }

    @Test
    @DisplayName("captured / not-captured serialize as real booleans")
    void knownAnswersSerializeAsBooleans() throws Exception {
        var received = mapper.readTree(mapper.writeValueAsString(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.PAYMENT_RECEIVED).build()));
        assertThat(received.get("fundsCaptured").isBoolean()).isTrue();
        assertThat(received.get("fundsCaptured").asBoolean()).isTrue();

        var awaiting = mapper.readTree(mapper.writeValueAsString(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.AWAITING_PAYMENT).build()));
        assertThat(awaiting.get("fundsCaptured").isBoolean()).isTrue();
        assertThat(awaiting.get("fundsCaptured").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("stage serializes as its enum name — the value clients switch on")
    void stageSerializesAsName() throws Exception {
        for (PaymentResponse.Stage stage : PaymentResponse.Stage.values()) {
            String json = mapper.writeValueAsString(
                    PaymentResponse.builder().stage(stage).build());
            assertThat(mapper.readTree(json).get("stage").asText()).isEqualTo(stage.name());
        }
    }
}
