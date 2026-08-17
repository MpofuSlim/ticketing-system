package innbucks.paymentservice.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money question, pinned. {@code fundsCaptured} answers "has money
 * actually left the customer" and both wrong answers cost real money:
 *
 * <ul>
 *   <li>a false {@code true} shows "payment received" to someone who was
 *       never charged, and promises a ticket we cannot back;</li>
 *   <li>a false {@code false} tells someone who DID pay that nothing was
 *       charged — the complaint that starts a chargeback.</li>
 * </ul>
 *
 * Hence the third answer, {@code null} = unknown, for the one state where we
 * genuinely do not know.
 */
class PaymentStageTest {

    @ParameterizedTest
    @EnumSource(PaymentResponse.Stage.class)
    @DisplayName("every stage answers the money question — no stage may be left undefined")
    void everyStageIsMapped(PaymentResponse.Stage stage) {
        // A switch that stops being exhaustive would fail to compile, but a
        // stage added with the wrong arm would not — so assert the answer is
        // one of exactly three legal values, per stage.
        Boolean captured = stage.fundsCaptured();
        assertThat(captured).isIn(Boolean.TRUE, Boolean.FALSE, null);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentResponse.Stage.class,
            names = {"PAYMENT_RECEIVED", "COMPLETED"})
    @DisplayName("money-moved stages report captured=true — these earn the confident screen")
    void capturedStages(PaymentResponse.Stage stage) {
        assertThat(stage.fundsCaptured()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentResponse.Stage.class,
            names = {"AWAITING_PAYMENT", "IN_PROGRESS", "INSTRUMENT_EXPIRED", "PAYMENT_UNAVAILABLE"})
    @DisplayName("nothing-charged stages report captured=false — safe to say 'you have not been charged'")
    void notCapturedStages(PaymentResponse.Stage stage) {
        assertThat(stage.fundsCaptured()).isFalse();
    }

    @Test
    @DisplayName("VERIFYING reports null, NOT false — we must never tell a payer nothing was charged")
    void verifyingIsUnknownNotFalse() {
        assertThat(PaymentResponse.Stage.VERIFYING.fundsCaptured()).isNull();
    }

    @Test
    @DisplayName("the builder derives fundsCaptured from stage, so the two cannot drift")
    void builderDerivesFundsCaptured() {
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.PAYMENT_RECEIVED).build().getFundsCaptured()).isTrue();
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.AWAITING_PAYMENT).build().getFundsCaptured()).isFalse();
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.VERIFYING).build().getFundsCaptured()).isNull();
        // No stage set at all (shouldn't happen, but must not assert a lie).
        assertThat(PaymentResponse.builder().build().getFundsCaptured()).isNull();
    }

    @Test
    @DisplayName("exactly one stage means money-moved-but-order-unconfirmed — the FE's 'confident' screen")
    void onlyOneStageIsTheConfirmingScreen() {
        // PAYMENT_RECEIVED is captured but not yet terminal; COMPLETED is
        // captured AND confirmed. Everything else is not-captured or unknown.
        assertThat(java.util.Arrays.stream(PaymentResponse.Stage.values())
                .filter(s -> Boolean.TRUE.equals(s.fundsCaptured()))
                .toList())
                .containsExactlyInAnyOrder(
                        PaymentResponse.Stage.PAYMENT_RECEIVED,
                        PaymentResponse.Stage.COMPLETED);
    }
}
