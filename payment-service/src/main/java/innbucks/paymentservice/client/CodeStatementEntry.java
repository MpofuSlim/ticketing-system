package innbucks.paymentservice.client;

import java.time.LocalDateTime;

/**
 * One code transaction from the merchant's mini statement
 * ({@code GET /api/code/{accountId}/miniStatement}) — InnBucks' own record of
 * a code's lifecycle, used by settlement reconciliation as the counterparty
 * truth to match our ledger against.
 *
 * @param code        the InnBucks code (matches {@code payment.innbucks_code})
 * @param amountCents amount in CENTS (statement serialises it as a string)
 * @param state       InnBucks state as sent (Pending / Claimed / Paid / ...)
 * @param createdAt   statement {@code createDate} ("yyyy-MM-dd HH:mm:ss" —
 *                    no zone on the wire; treated as UTC by convention, the
 *                    same assumption the status poller makes); null when
 *                    absent/unparseable
 */
public record CodeStatementEntry(
        String code,
        Long amountCents,
        String state,
        LocalDateTime createdAt) {

    /** InnBucks' "finalised by the customer" states — money arrived. */
    public boolean isFinalised() {
        if (state == null) return false;
        String s = state.trim().toUpperCase(java.util.Locale.ROOT);
        return s.equals("CLAIMED") || s.equals("PAID");
    }
}
