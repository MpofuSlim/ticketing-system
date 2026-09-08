package innbucks.paymentservice.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The EcoCash currency allow-list — the guard that stops an unsupported
 * currency reaching the wire, where EcoCash answers with their edge WAF's
 * {@code text/html} page rather than a business rejection and the row is
 * pinned open forever.
 *
 * <p>Each case here is a footgun measured on preprod, not a hypothetical.
 */
class EcocashCurrenciesTest {

    @Test
    @DisplayName("the two alpha-3 codes EIP accepts are supported")
    void supportedCodes() {
        assertThat(EcocashCurrencies.isSupported("USD")).isTrue();
        assertThat(EcocashCurrencies.isSupported("ZWG")).isTrue();
        assertThat(EcocashCurrencies.SUPPORTED).containsExactlyInAnyOrder("USD", "ZWG");
    }

    @ParameterizedTest
    @ValueSource(strings = {"840", "924"})
    @DisplayName("the NUMERIC ISO code is NOT supported — the quiet trap that parks every payment IN_DOUBT")
    void numericIsoCodesAreRefused(String numeric) {
        // EcoCash's test data writes the pair as "USD/840". Sending the numeric
        // half is accepted by the charge (which echoes it back verbatim) but the
        // QUERY normalises it to "USD" — and the query echo is what
        // EcocashPaymentService.echoMismatch compares against the ledger. So a
        // numeric currency would silently park 100% of COMPLETED payments
        // IN_DOUBT with no error and no boot warning.
        assertThat(EcocashCurrencies.isSupported(numeric)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZAR", "ZWL", "GBP", "EUR", "usdollar"})
    @DisplayName("anything outside the list is refused — the fleet's fail-closed currency posture")
    void unsupportedCodesAreRefused(String currency) {
        assertThat(EcocashCurrencies.isSupported(currency)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", " usd ", "Usd", "\tZWG\n"})
    @DisplayName("case and surrounding whitespace are canonicalised, not rejected")
    void canonicalisesCaseAndWhitespace(String raw) {
        assertThat(EcocashCurrencies.isSupported(raw)).isTrue();
        assertThat(EcocashCurrencies.canonical(raw)).isIn("USD", "ZWG");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("null/blank fails CLOSED — an absent currency is never treated as the base one")
    void nullAndBlankFailClosed(String raw) {
        assertThat(EcocashCurrencies.canonical(raw)).isNull();
        assertThat(EcocashCurrencies.isSupported(raw)).isFalse();
    }

    @Test
    @DisplayName("canonical() is what callers must store AND send — the echo guard rests on one string")
    void canonicalIsIdempotent() {
        String once = EcocashCurrencies.canonical(" usd ");
        assertThat(once).isEqualTo("USD");
        assertThat(EcocashCurrencies.canonical(once)).isEqualTo(once);
    }
}
