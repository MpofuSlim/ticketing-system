package com.innbucks.eventservice;

import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * See sibling Postgres ITs in user / booking / seat / loyalty for the
 * rationale. Empty body — context refresh against real Postgres is the
 * test.
 */
class EventServiceApplicationPostgresIT extends PostgresIntegrationTestBase {

    @Test
    void contextLoadsAgainstPostgres() {
    }
}
