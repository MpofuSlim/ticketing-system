package com.innbucks.bookingservice;

import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Boot the full Spring context against a real Postgres via Testcontainers.
 * Empty body — the value is that the context refreshes successfully:
 * every Flyway migration applies cleanly to a virgin Postgres 16 schema
 * AND every @Entity column maps to a real column (ddl-auto=validate). The
 * H2 "test" profile uses create-drop so it can't catch entity/migration
 * drift; this test can, and it's the regression that matters most when
 * Flyway picks up a new V*.sql.
 */
class BookingServiceApplicationPostgresIT extends PostgresIntegrationTestBase {

    @Test
    void contextLoadsAgainstPostgres() {
    }
}
