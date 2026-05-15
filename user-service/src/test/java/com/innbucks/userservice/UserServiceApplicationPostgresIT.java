package com.innbucks.userservice;

import com.innbucks.userservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Smoke test against a real Postgres via Testcontainers. The body is empty
 * — the value is what booting the Spring context proves:
 *
 * <ul>
 *   <li>Every V*.sql migration in {@code src/main/resources/db/migration}
 *       applies cleanly to a virgin Postgres 16 database.</li>
 *   <li>Every @Entity column maps to a real column with a compatible type
 *       (ddl-auto=validate). Catches drift between entities and migrations
 *       — the H2 "test" profile uses ddl-auto=create-drop which generates
 *       the schema from the entities, so it can't see this class of bug.</li>
 *   <li>The full bean graph constructs without missing config — same as
 *       the H2 smoke test, but against the production datasource flavour.</li>
 * </ul>
 */
class UserServiceApplicationPostgresIT extends PostgresIntegrationTestBase {

    @Test
    void contextLoadsAgainstPostgres() {
    }
}
