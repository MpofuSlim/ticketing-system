package com.innbucks.bookingservice.event;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Boot 4 dropped its Kafka auto-config; spring-kafka the library is still on
 * the classpath, so we wire ProducerFactory + KafkaTemplate ourselves. acks=all
 * with idempotence prevents silent drops on transient broker hiccups.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.events.kafka.delivery-timeout-ms:30000}") int deliveryTimeoutMs,
            @Value("${app.events.kafka.request-timeout-ms:5000}") int requestTimeoutMs,
            @Value("${app.events.kafka.max-block-ms:5000}") int maxBlockMs) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Bound how long the producer keeps retrying a record before failing
        // it. Defaults are dev-friendly: a misconfigured broker (e.g. wrong
        // advertised listener) surfaces as a single warn per event instead of
        // an infinite Sender retry loop. Tune up in prod if needed.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
