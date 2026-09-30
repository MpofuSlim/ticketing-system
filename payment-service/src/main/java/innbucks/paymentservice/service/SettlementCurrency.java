package innbucks.paymentservice.service;

import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;

import java.util.Locale;

/**
 * The currency guard for the rails that collect in ONE currency: the InnBucks
 * 2D-code rail and the ZimSwitch card rail both settle into the cell's merchant
 * account, which is denominated in the cell currency ({@code innbucks.currency},
 * USD on the ZW cell). EcoCash has its own allow-list ({@code EcocashCurrencies}).
 *
 * <p>Why this has to exist: the InnBucks Merchant API's code generation takes
 * an amount in cents and NO currency — the code is charged in whatever the
 * merchant account holds. A ZAR 100.00 voucher order therefore minted a code
 * for USD 100.00, and a paid code confirmed the order as "ZAR 100.00 paid", so
 * the voucher issued against the wrong money. The card rail does send a
 * currency, but nothing checked that the merchant entity settles in it. Both
 * now refuse an order priced in any other currency BEFORE the hold, the ledger
 * row and the wire, the same place EcoCash refuses one.
 */
final class SettlementCurrency {

    private SettlementCurrency() {
    }

    /**
     * The order's currency, canonicalised, when it IS the cell currency;
     * otherwise a 422 naming the rail (the controller renders it for the
     * client, which offers another method). A blank order currency inherits
     * the cell currency — bookings carry none (single-country per cell).
     */
    static String requireCellCurrency(String orderCurrency, String cellCurrency, String railName) {
        String cell = canonical(cellCurrency);
        if (cell == null) {
            // Deployment fault, not the customer's: refuse rather than guess.
            throw new InvalidPaymentRequestException(
                    railName + " payments are not available on this deployment", 503);
        }
        String order = canonical(orderCurrency);
        if (order == null) {
            return cell;
        }
        if (!order.equals(cell)) {
            throw new InvalidPaymentRequestException(
                    railName + " cannot take payment in " + order
                            + " — please use another payment method", 422);
        }
        return order;
    }

    private static String canonical(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
