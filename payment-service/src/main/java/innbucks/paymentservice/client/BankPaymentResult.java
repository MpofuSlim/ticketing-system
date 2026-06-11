package innbucks.paymentservice.client;

/**
 * Classified outcome of a {@code POST /bank/api/payment} submission. Reuses
 * the existing {@link PaymentOutcome} vocabulary so the orchestration switch
 * in {@code InnbucksPaymentService} is unchanged:
 * <ul>
 *   <li>{@code COMPLETED} — the bank confirmed the debit; {@code reference}
 *       carries its transaction reference.</li>
 *   <li>{@code PROCESSING} — 2xx but no recognisable success/failure marker
 *       in the body. The ledger row stays PENDING and the reconciler
 *       resolves via transaction inquiry. (Conservative by design: with an
 *       inquiry endpoint available, an ambiguous answer is never guessed.)</li>
 *   <li>{@code REJECTED_*} — the bank declined; {@code code}/{@code message}
 *       carry its reason.</li>
 * </ul>
 */
public record BankPaymentResult(
        PaymentOutcome outcome,
        String reference,
        String code,
        String message
) {}
