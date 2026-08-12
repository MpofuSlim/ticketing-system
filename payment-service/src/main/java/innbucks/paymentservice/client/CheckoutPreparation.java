package innbucks.paymentservice.client;

/**
 * Outcome of {@code POST /v1/checkouts} (COPYandPAY prepare-checkout).
 *
 * @param created       gateway returned {@code 000.200.100} AND an {@code id};
 *                      without the id there is no payment form to render
 * @param checkoutId    the {@code id} — the handle the widget loads with and
 *                      every status query keys on. Stays valid for reuse until
 *                      a payment is finalized, and no later than 30 minutes
 * @param integrity     SRI digest for the widget {@code <script integrity=...>}
 *                      tag, returned when {@code integrity=true} was sent.
 *                      Null when the gateway omitted it — the FE then loads
 *                      the script unpinned, which is a downgrade worth logging
 * @param resultCode    raw {@code result.code} (journalled verbatim)
 * @param resultMessage raw {@code result.description}
 * @param ndc           gateway correlation handle — quote it to ZimSwitch support
 */
public record CheckoutPreparation(
        boolean created,
        String checkoutId,
        String integrity,
        String resultCode,
        String resultMessage,
        String ndc) {
}
