package com.innbucks.userservice.testsupport;

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
 * Base for *PostgresIT.java tests that need a real Postgres — exercises the
 * production Flyway → JPA schema flow that H2 can only approximate. Catches
 * schema drift between the V*.sql migrations and @Entity columns: if an
 * entity adds a field the migrations don't, Hibernate's {@code ddl-auto:
 * validate} (set in {@code application-it.yaml}) refuses to boot, surfacing
 * the drift at CI time rather than at the first prod deploy.
 *
 * <p>One static container per JVM (Testcontainers handles the lifecycle).
 * Subclasses don't need {@code @ActiveProfiles}, this base sets {@code "it"}.
 *
 * <p>Skipped automatically when Docker isn't reachable, so dev machines
 * without Docker still see green Surefire builds — only Failsafe IT runs
 * are affected.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
public abstract class PostgresIntegrationTestBase {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("user_it")
            .withUsername("user_svc")
            .withPassword("user_svc")
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
