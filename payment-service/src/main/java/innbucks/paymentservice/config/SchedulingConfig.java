package innbucks.paymentservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@link org.springframework.scheduling.annotation.Scheduled @Scheduled}
 * processing across the service. Required for
 * {@link innbucks.paymentservice.reconciliation.ReconciliationJob} and any
 * future periodic task. Spring Boot does not auto-enable this — the
 * annotation is opt-in.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
