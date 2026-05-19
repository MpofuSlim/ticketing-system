package innbucks.paymentservice.messaging;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionEventPublisherTest {

    private static TransactionCompletedEvent event(UUID txId, String status) {
        return new TransactionCompletedEvent(
                UUID.randomUUID(),
                Instant.now(),
                txId,
                "TRANSFER",
                status,
                "+254777224008",
                "A000001",
                "A000002",
                new BigDecimal("100.00"),
                null, null, null,
                LocalDate.now(),
                null,
                Instant.now(),
                Instant.now(),
                "oradian-1", "ref-1", null,
                null, null,
                "corr-xyz"
        );
    }

    @Test
    void publishToKafka_sendsToTransactionCompletedTopic_keyedByTransactionId() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(String.class), any(String.class), any()))
                .thenReturn(CompletableFuture.completedFuture(stubMetadata()));

        UUID txId = UUID.randomUUID();
        new TransactionEventPublisher(template).publishToKafka(event(txId, "SUCCEEDED"));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(template).send(topicCaptor.capture(), keyCaptor.capture(), bodyCaptor.capture());

        assertEquals(PaymentTopics.TRANSACTION_COMPLETED, topicCaptor.getValue(),
                "must publish to the dedicated payment.transaction.completed topic");
        assertEquals(txId.toString(), keyCaptor.getValue(),
                "keying by transactionId means all events for one transaction land on the " +
                        "same partition — preserves ordering per-tx for any future consumer " +
                        "that cares about state transitions");
        assertInstanceOf(TransactionCompletedEvent.class, bodyCaptor.getValue());
    }

    @Test
    void publishToKafka_swallowsBrokerFailures_doesNotPropagate() {
        // Local DB commit has already happened by the time AFTER_COMMIT runs.
        // Throwing here would do nothing useful (we can't roll back the
        // ledger row), and any uncaught exception in a TransactionalEventListener
        // logs but doesn't affect the caller. The publisher catches
        // explicitly so the log line stays at our level (ERROR with txId
        // context) rather than Spring's generic listener-failure stack.
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(String.class), any(String.class), any()))
                .thenThrow(new RuntimeException("broker unreachable"));

        assertDoesNotThrow(() ->
                new TransactionEventPublisher(template)
                        .publishToKafka(event(UUID.randomUUID(), "SUCCEEDED")));

        verify(template).send(eq(PaymentTopics.TRANSACTION_COMPLETED), any(String.class), any());
    }

    @Test
    void publishToKafka_handlesAsyncFailures_inSendCompletionCallback() {
        // Async path: send() returns a CompletableFuture that completes
        // exceptionally. The publisher attaches a whenComplete that logs
        // and doesn't re-throw. Pin that the future completing with an
        // error doesn't escape as an exception.
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("ack timeout"));
        when(template.send(any(String.class), any(String.class), any())).thenReturn(failed);

        assertDoesNotThrow(() ->
                new TransactionEventPublisher(template)
                        .publishToKafka(event(UUID.randomUUID(), "FAILED")));
    }

    /** Minimal RecordMetadata + SendResult for the success path of send(). */
    private static SendResult<String, Object> stubMetadata() {
        TopicPartition tp = new TopicPartition(PaymentTopics.TRANSACTION_COMPLETED, 0);
        RecordMetadata md = new RecordMetadata(tp, 0L, 0, System.currentTimeMillis(), 0, 0);
        return new SendResult<>(null, md);
    }
}
