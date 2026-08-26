package innbucks.paymentservice.entity;

/**
 * Which collection rail a {@link Payment} runs on. Stored as
 * {@code payment.payment_rail} (V13); the vocabulary is mirrored by the
 * {@code chk_payment_rail} DB constraint — extend both together.
 *
 * <ul>
 *   <li>{@link #INNBUCKS_CODE} — InnBucks Merchant API 2D-code: a PAYMENT
 *       code/QR the customer approves in their own InnBucks app
 *       ({@code docs/api/innbucks-merchant-api.md}). Every pre-V13 row.</li>
 *   <li>{@link #ZIMSWITCH_CARD} — ZimSwitch Online (COPYandPAY) card
 *       checkout: the FE renders the gateway's PCI-scoped widget against a
 *       server-prepared {@code checkoutId}
 *       ({@code docs/api/zimswitch-copyandpay.md}).</li>
 *   <li>{@link #ECOCASH} — EcoCash Instant Payment (EIP): a wallet-debit
 *       charge that pushes a PIN prompt to the customer's phone; resolved by
 *       polling the Query endpoint keyed on our {@code clientCorrelator}
 *       ({@code docs/api/ecocash-eip.md}). Extended in V14.</li>
 * </ul>
 */
public enum PaymentRail {
    INNBUCKS_CODE,
    ZIMSWITCH_CARD,
    ECOCASH
}
