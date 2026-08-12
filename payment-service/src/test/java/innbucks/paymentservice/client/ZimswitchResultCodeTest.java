package innbucks.paymentservice.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the COPYandPAY result-code families (docs/api/zimswitch-copyandpay.md)
 * onto our classifier. The dangerous edges each get their own case:
 * an unknown code must NEVER classify as success, a blank/null code must
 * never classify as a terminal decline, and 200.300.404 ("no payment for
 * this checkout") must never read as a decline — it is the normal answer
 * while the shopper still has the form open.
 */
class ZimswitchResultCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "000.000.000",  // success, live systems
            "000.000.100",  // success family
            "000.100.110",  // success, test/integrator
            "000.100.112",  // success, connector test mode
            "000.300.000",  // two-step / success family (000.3)
            "000.600.000",  // success family (000.6)
    })
    void successFamilies_classifyAsSuccess(String code) {
        assertThat(ZimswitchResultCode.classify(code)).isEqualTo(ZimswitchResultCode.SUCCESS);
        assertThat(ZimswitchResultCode.classify(code).isPaid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "000.400.000",  // succeeded, review manually (fraud suspicion)
            "000.400.010",  // succeeded, review (AVS)
            "000.400.020",  // succeeded, review (CVV)
            "000.400.100",  // succeeded, feature disabled -> review
    })
    void manualReviewFamily_isPaidButFlagged(String code) {
        assertThat(ZimswitchResultCode.classify(code)).isEqualTo(ZimswitchResultCode.SUCCESS_MANUAL_REVIEW);
        assertThat(ZimswitchResultCode.classify(code).isPaid()).isTrue();
    }

    @Test
    @DisplayName("000.400.030 is OUTSIDE the manual-review window (0[^3]) — a risk decline, not a success")
    void riskDecline_isNotManualReview() {
        assertThat(ZimswitchResultCode.classify("000.400.030")).isEqualTo(ZimswitchResultCode.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"000.200.000", "000.200.100", "000.200.200"})
    void pendingFamily_neverTerminal(String code) {
        assertThat(ZimswitchResultCode.classify(code)).isEqualTo(ZimswitchResultCode.PENDING);
        assertThat(ZimswitchResultCode.classify(code).isPaid()).isFalse();
    }

    @Test
    @DisplayName("200.300.404 = no payment on the checkout — its own state, NEVER a decline")
    void noPaymentSession_isCheckoutNotFound() {
        assertThat(ZimswitchResultCode.classify("200.300.404"))
                .isEqualTo(ZimswitchResultCode.CHECKOUT_NOT_FOUND);
    }

    @ParameterizedTest
    @CsvSource({
            "800.100.153", // declined - invalid CVV
            "800.100.151", // declined - invalid card
            "100.396.101", // cancelled by user
            "600.200.500", // invalid payment data / config
            "200.300.403", // validation error (not the 404 not-found)
            "999.999.999", // unknown future code — must fail closed, never succeed
    })
    void everythingElse_classifiesAsRejected(String code) {
        assertThat(ZimswitchResultCode.classify(code)).isEqualTo(ZimswitchResultCode.REJECTED);
        assertThat(ZimswitchResultCode.classify(code).isPaid()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("unreadable code = PENDING, never REJECTED — guessing terminal on a blank is the money-losing path")
    void unreadableCode_isPendingNotRejected(String code) {
        assertThat(ZimswitchResultCode.classify(code)).isEqualTo(ZimswitchResultCode.PENDING);
    }

    @Test
    @DisplayName("the prepare-checkout success constant matches the doc and stays out of the paid set")
    void checkoutCreatedConstant() {
        assertThat(ZimswitchResultCode.CHECKOUT_CREATED).isEqualTo("000.200.100");
        // Deliberate: fed through the payment classifier it reads PENDING,
        // which is why prepare responses are classified by equality on the
        // constant, never through classify().
        assertThat(ZimswitchResultCode.classify(ZimswitchResultCode.CHECKOUT_CREATED))
                .isEqualTo(ZimswitchResultCode.PENDING);
    }
}
