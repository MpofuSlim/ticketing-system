package innbucks.paymentservice.messaging;

/**
 * Kafka topic names used by payment-service. Single source of truth so
 * config, producer, and tests all reference the same string.
 */
public final class PaymentTopics {

    private PaymentTopics() {
    }

    /**
     * Terminal-state events for the {@code transactions} ledger
     * (SUCCEEDED or FAILED). Producer: payment-service. Consumers (when
     * built): notification service (SMS/push on success), statement
     * generator, BI/analytics, audit warehouse.
     */
    public static final String TRANSACTION_COMPLETED = "payment.transaction.completed";
}
