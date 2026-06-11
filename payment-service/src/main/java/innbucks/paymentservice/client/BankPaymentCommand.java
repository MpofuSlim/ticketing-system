package innbucks.paymentservice.client;

import java.math.BigDecimal;

/**
 * Outbound payment submission to {@code POST /bank/api/payment}.
 * {@code participantReference} is our stable {@code TKT-PMT-<uuid>} payment
 * reference — the upstream-side correlation/idempotency handle, and the key
 * the reconciler later passes to transaction inquiry as
 * {@code originalParticipantReference}.
 */
public record BankPaymentCommand(
        BigDecimal amount,
        String currency,
        String narration,
        String sourceAccount,
        String destinationAccount,
        String participantReference
) {}
