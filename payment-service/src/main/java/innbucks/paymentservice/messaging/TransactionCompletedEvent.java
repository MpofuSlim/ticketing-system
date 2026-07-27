package innbucks.paymentservice.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * In-JVM domain event emitted by {@link innbucks.paymentservice.service.TransactionService}
 * once a ledger row reaches a terminal state (SUCCEEDED or FAILED). Published
 * via Spring's {@code ApplicationEventPublisher} from within the @Transactional
 * method that flips the row; {@link PaymentNotificationListener} fires
 * AFTER_COMMIT, so a confirmation is never sent for a row that ultimately
 * rolled back.
 *
 * <p>Includes the customer's full phone number unmasked — the AFTER_COMMIT
 * notification listener needs it to send the WhatsApp confirmation. The audit's
 * log-masking rule applies to OBSERVABILITY logs, not to this in-process event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionCompletedEvent(
        UUID eventId,
        Instant eventTime,
        UUID transactionId,
        String type,
        String status,
        String customerPhone,
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
        String oradianCommandId,
        String failureCode,
        String failureMessage,
        String correlationId
) {
}
