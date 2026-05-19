package innbucks.paymentservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Producer-side Kafka wiring for the {@code payment.transaction.completed}
 * topic. We're explicit about config because Spring Boot's defaults are
 * tuned for low-stakes streaming, not money-movement events:
 *
 * <ul>
 *   <li><b>{@code acks=all}</b> — every replica must ack before the producer
 *       considers the publish successful. Caveat: pairs with the broker's
 *       {@code min.insync.replicas=2} (set in topic config or broker
 *       config) to actually mean "at least 2 replicas". Default acks=1
 *       would lose the event on a leader crash before replication.</li>
 *   <li><b>{@code enable.idempotence=true}</b> — producer-side de-dup so
 *       a retry within the same producer session can't double-publish
 *       the same event. Forces acks=all + max-in-flight ≤ 5 + retries > 0
 *       automatically.</li>
 *   <li><b>JsonSerializer + add-type-headers=false</b> — payload is just
 *       JSON; we don't want the type-header coupling between producer
 *       and consumer (consumer can deserialise with its own DTO).</li>
 * </ul>
 *
 * <p>Topic is declared via {@link NewTopic} so a fresh cluster gets it
 * created at startup with the right partition / replication settings.
 * Auto-create-topics on the broker is a dev convenience — prod brokers
 * should have it disabled, which makes the {@link NewTopic} bean the
 * actual provisioning path.
 */
@Configuration
public class KafkaProducerConfig {

    private final String bootstrapServers;
    private final ObjectMapper objectMapper;

    public KafkaProducerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectMapper objectMapper) {
        this.bootstrapServers = bootstrapServers;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ProducerFactory<String, Object> paymentEventProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // No type headers — keep the wire format provider-neutral.
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        // Strong delivery semantics for money events.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);
        // Reuse the application's ObjectMapper so date/time + JsonInclude
        // serialisation matches the rest of the service.
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> paymentEventKafkaTemplate(
            ProducerFactory<String, Object> paymentEventProducerFactory) {
        return new KafkaTemplate<>(paymentEventProducerFactory);
    }

    /**
     * Declared so a fresh cluster gets the topic at startup with explicit
     * partition + replication settings. Override per env via
     * {@code PAYMENT_TX_TOPIC_PARTITIONS} / {@code PAYMENT_TX_TOPIC_REPLICAS}.
     * Replication factor of 1 is dev only; bump to 3 in any environment
     * with multiple brokers.
     */
    @Bean
    public NewTopic transactionCompletedTopic(
            @Value("${payment-service.kafka.transaction-completed.partitions:3}") int partitions,
            @Value("${payment-service.kafka.transaction-completed.replicas:1}") short replicas) {
        return TopicBuilder.name(PaymentTopics.TRANSACTION_COMPLETED)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
