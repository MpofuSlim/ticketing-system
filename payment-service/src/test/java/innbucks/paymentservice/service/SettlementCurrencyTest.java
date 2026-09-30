package innbucks.paymentservice.service;

import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single-currency rails (InnBucks code, card) settle into the cell's
 * merchant account, so an order priced in anything else is refused — never
 * charged as the same number in the account's currency.
 */
class SettlementCurrencyTest {

    @Test
    void zarVoucherOnTheUsdCell_isRefused422() {
        assertThatThrownBy(() -> SettlementCurrency.requireCellCurrency("ZAR", "USD", "InnBucks"))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage("InnBucks cannot take payment in ZAR — please use another payment method")
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(422));
    }

    @Test
    void zwgOnTheUsdCell_isRefusedToo() {
        assertThatThrownBy(() -> SettlementCurrency.requireCellCurrency("ZWG", "USD", "Card"))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("cannot take payment in ZWG");
    }

    @Test
    void cellCurrency_isAccepted_andCanonicalised() {
        assertThat(SettlementCurrency.requireCellCurrency(" usd ", "USD", "InnBucks")).isEqualTo("USD");
    }

    @Test
    void blankOrderCurrency_inheritsTheCellCurrency() {
        // Bookings carry no currency: single-country per cell.
        assertThat(SettlementCurrency.requireCellCurrency(null, "usd", "InnBucks")).isEqualTo("USD");
        assertThat(SettlementCurrency.requireCellCurrency("  ", "USD", "InnBucks")).isEqualTo("USD");
    }

    @Test
    void blankCellCurrency_isADeploymentFault_503() {
        assertThatThrownBy(() -> SettlementCurrency.requireCellCurrency("USD", " ", "InnBucks"))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .satisfies(e -> assertThat(((InvalidPaymentRequestException) e).getStatusCode()).isEqualTo(503));
    }
}
