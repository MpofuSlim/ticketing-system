package innbucks.paymentservice;

import innbucks.paymentservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the application context boots against the Testcontainers
 * Postgres, Flyway runs every migration in {@code src/main/resources/db/migration},
 * and Hibernate's {@code ddl-auto: validate} (set by {@code application-it.yaml})
 * confirms every {@code @Entity} column matches a column the migrations
 * created. Catches the most common class of bug — adding a field to an
 * entity and forgetting the migration — before any prod deploy.
 */
class PaymentServiceApplicationPostgresIT extends PostgresIntegrationTestBase {

    @Test
    void contextLoads() {
        // Empty test body: if Boot started + Flyway ran + Hibernate validate
        // passed, the schema and entities agree. Any divergence shows up
        // here as a context-load failure with a precise "missing column" /
        // "unknown table" message.
    }
}
