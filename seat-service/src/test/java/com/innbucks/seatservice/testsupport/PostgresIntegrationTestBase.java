package com.innbucks.seatservice.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for *PostgresIT.java tests — see loyalty-service /
 * user-service equivalents for the rationale (Flyway + ddl-auto=validate
 * against a real Postgres catches schema drift the H2 path can't).
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
public abstract class PostgresIntegrationTestBase {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seat_it")
            .withUsername("seat")
            .withPassword("seat")
            .withReuse(true);

    @BeforeAll
    static void startContainer() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
