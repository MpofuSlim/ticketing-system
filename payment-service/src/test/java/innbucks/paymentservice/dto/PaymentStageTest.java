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

    @Test
    @DisplayName("the whole money table, pinned explicitly — the only assertion that can actually fail")
    void theMoneyTableIsExact() {
        // Deliberately exhaustive and literal. An earlier version asserted
        // each answer was "one of TRUE/FALSE/null", which no mapping can ever
        // violate — a vacuous test that would have green-lit any change.
        java.util.Map<PaymentResponse.Stage, Boolean> actual = new java.util.LinkedHashMap<>();
        for (PaymentResponse.Stage stage : PaymentResponse.Stage.values()) {
            actual.put(stage, stage.fundsCaptured());
        }

        java.util.Map<PaymentResponse.Stage, Boolean> expected = new java.util.LinkedHashMap<>();
        expected.put(PaymentResponse.Stage.AWAITING_PAYMENT, Boolean.FALSE);
        expected.put(PaymentResponse.Stage.IN_PROGRESS, Boolean.FALSE);
        expected.put(PaymentResponse.Stage.INSTRUMENT_EXPIRED, null);
        expected.put(PaymentResponse.Stage.PAYMENT_UNAVAILABLE, Boolean.FALSE);
        expected.put(PaymentResponse.Stage.PAYMENT_RECEIVED, Boolean.TRUE);
        expected.put(PaymentResponse.Stage.COMPLETED, Boolean.TRUE);
        expected.put(PaymentResponse.Stage.VERIFYING, null);

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
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
            names = {"AWAITING_PAYMENT", "IN_PROGRESS", "PAYMENT_UNAVAILABLE"})
    @DisplayName("nothing-charged stages report captured=false — safe to say 'you have not been charged'")
    void notCapturedStages(PaymentResponse.Stage stage) {
        assertThat(stage.fundsCaptured()).isFalse();
    }

    @Test
    @DisplayName("unresolved stages report null, NOT false — never tell a possible payer nothing was charged")
    void unresolvedStagesAreUnknownNotFalse() {
        assertThat(PaymentResponse.Stage.VERIFYING.fundsCaptured()).isNull();
        // INSTRUMENT_EXPIRED is only ever SEEN while the reconciler has not
        // concluded the instrument went unpaid — and it explicitly refuses to
        // conclude that for rows whose upstream status is unreadable. A row
        // proven unpaid becomes terminal EXPIRED and stops being replayed.
        assertThat(PaymentResponse.Stage.INSTRUMENT_EXPIRED.fundsCaptured()).isNull();
    }

    @Test
    @DisplayName("fundsCaptured is derived with no setter, so it CANNOT be set to contradict the stage")
    void fundsCapturedIsDerivedNotSettable() {
        // There must be no way to write it independently — no field, no
        // setter, no builder method. Reflection is the test: a future edit
        // reintroducing a backing field would resurrect Lombok's setter and
        // with it the order-dependent contradiction this replaced.
        assertThat(java.util.Arrays.stream(PaymentResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("fundsCaptured must stay a derived getter, never a field")
                .doesNotContain("fundsCaptured");
        assertThat(java.util.Arrays.stream(PaymentResponse.PaymentResponseBuilder.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .as("the builder must not expose a way to set it independently")
                .doesNotContain("fundsCaptured");
    }

    @Test
    @DisplayName("the derived value tracks whatever stage was built")
    void builderDerivesFundsCaptured() {
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.PAYMENT_RECEIVED).build().getFundsCaptured()).isTrue();
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.AWAITING_PAYMENT).build().getFundsCaptured()).isFalse();
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.VERIFYING).build().getFundsCaptured()).isNull();
        assertThat(PaymentResponse.builder()
                .stage(PaymentResponse.Stage.INSTRUMENT_EXPIRED).build().getFundsCaptured()).isNull();
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
