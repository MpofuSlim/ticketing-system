package com.innbucks.loyaltyservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LoyaltyServiceApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context boots end-to-end with the test profile
        // (H2 in PostgreSQL mode, Flyway disabled, ddl-auto=create-drop) so
        // entity↔Hibernate wiring stays sound.
    }
}
