package innbucks.paymentservice.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Domain event emitted by {@link innbucks.paymentservice.service.TransactionService}
 * once a ledger row reaches a terminal state (SUCCEEDED or FAILED). Used in
 * two ways:
 *
 * <ul>
 *   <li><b>Inside the JVM</b> — published via Spring's
 *       {@code ApplicationEventPublisher} from within the @Transactional
 *       method that flips the row. The
 *       {@code TransactionEventPublisher.publishToKafka} listener fires
 *       AFTER the transaction commits, so an event is never emitted for
 *       a row that ultimately rolled back.</li>
 *   <li><b>On Kafka</b> — serialised as JSON to the
 *       {@code payment.transaction.completed} topic. Keyed by transaction
 *       id so all events for one transaction go to the same partition
 *       (ordered consumption per tx).</li>
 * </ul>
 *
 * <p>Includes the customer's full phone number unmasked — downstream
 * consumers (the future notification service) need it to send the SMS /
 * push. Kafka is internal-network only; the consumers run inside our
 * security perimeter. The audit's log-masking rule applies to OBSERVABILITY
 * logs, not to internal messaging payloads.
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
