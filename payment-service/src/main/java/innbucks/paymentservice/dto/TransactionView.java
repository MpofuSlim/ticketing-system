package innbucks.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import innbucks.paymentservice.entity.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Customer-facing projection of a {@link Transaction} ledger row for
 * GET /payments/transactions. Drops the fields the customer doesn't
 * need to see and operators shouldn't expose: {@code correlationId}
 * (server-side trace key), {@code idempotencyKey} (anti-replay token —
 * leaking it would let an attacker craft conflicting replays for that
 * transaction).
 *
 * <p>Everything else is fair game on the customer's own history: status
 * lifecycle, both account IDs, amount + currency, Oradian's transaction
 * ID for receipt lookup, failure code + message so the FE can render
 * "your account is suspended" / "insufficient funds" UX without a
 * second call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "TransactionView", description = "Customer-facing view of one ledger row.")
public record TransactionView(
        UUID id,
        String type,
        String status,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String paymentMethodName,
        String notes,
        LocalDate transactionDate,
        String transactionBranchId,
        Instant createdAt,
        Instant completedAt,
        String oradianTransactionId,
        String oradianReferenceNumber,
        String failureCode,
        String failureMessage
) {

    public static TransactionView from(Transaction t) {
        return new TransactionView(
                t.getId(),
                t.getTransactionType() == null ? null : t.getTransactionType().name(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                t.getAmount(),
                t.getCurrency(),
                t.getPaymentMethodName(),
                t.getNotes(),
                t.getTransactionDate(),
                t.getTransactionBranchId(),
                t.getCreatedAt(),
                t.getCompletedAt(),
                t.getOradianTransactionId(),
                t.getOradianReferenceNumber(),
                t.getFailureCode(),
                t.getFailureMessage()
        );
    }
}
