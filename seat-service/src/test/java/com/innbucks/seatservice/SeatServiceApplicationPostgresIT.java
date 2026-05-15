package com.innbucks.seatservice;

import com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * See sibling Postgres ITs in user / booking / event / loyalty for the
 * rationale. Empty body — context refresh against real Postgres is the
 * test.
 */
class SeatServiceApplicationPostgresIT extends PostgresIntegrationTestBase {

    @Test
    void contextLoadsAgainstPostgres() {
    }
}
