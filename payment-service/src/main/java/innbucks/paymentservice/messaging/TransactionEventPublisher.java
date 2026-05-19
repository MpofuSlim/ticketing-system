package innbucks.paymentservice.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link TransactionCompletedEvent}s published by
 * {@link innbucks.paymentservice.service.TransactionService} and forwards
 * them to Kafka — AFTER the local transaction commits.
 *
 * <p>The {@code @TransactionalEventListener(phase = AFTER_COMMIT)} is the
 * critical part. If the ledger write rolls back (DB blip, validation
 * error in the same transaction, etc.), the listener doesn't fire — we
 * never emit an event saying "the transfer succeeded" when the row
 * actually rolled back. This is a lightweight outbox pattern: the
 * intent is recorded inside the transaction (the event publish), and
 * the side effect (Kafka send) is gated on the commit.
 *
 * <p>The remaining failure mode is "local commits, Kafka publish
 * fails". In that case the event is permanently lost from Kafka's view
 * even though the ledger row says SUCCEEDED. v1 logs at ERROR and
 * accepts the gap — a proper outbox table (write event to DB inside
 * the transaction, separate worker drains it to Kafka) is the v2 path.
 * For money systems where downstream consumers MUST never miss events,
 * upgrade before going to prod.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventPublisher {

    private final KafkaTemplate<String, Object> paymentEventKafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishToKafka(TransactionCompletedEvent event) {
        // Key by transactionId so all events for one transaction land on
        // the same partition (preserves ordering per-tx for any consumer
        // that cares about state transitions). We currently only emit
        // one event per transaction's terminal state, so this is more
        // forward-looking than load-bearing — if we ever add PENDING
        // events too, ordering matters.
        String key = event.transactionId().toString();
        try {
            paymentEventKafkaTemplate.send(PaymentTopics.TRANSACTION_COMPLETED, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // Local commit already happened — we can't roll it
                            // back. Log loudly so on-call + reconciliation can
                            // pick up the missing event. Real fix: outbox.
                            log.error("Kafka publish failed for TransactionCompletedEvent txId={} status={}",
                                    event.transactionId(), event.status(), ex);
                        } else {
                            log.info("Kafka publish OK txId={} status={} topic={} partition={} offset={}",
                                    event.transactionId(), event.status(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            // Synchronous failures (serializer error, mis-configured topic
            // when auto-create is off, etc.) land here. Same logic: don't
            // throw — the local commit's already done.
            log.error("Kafka publish failed synchronously for TransactionCompletedEvent txId={} status={}",
                    event.transactionId(), event.status(), ex);
        }
    }
}
