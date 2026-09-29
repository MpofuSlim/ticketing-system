package com.innbucks.userservice.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V43 must NEVER adopt an existing role. If an operator followed the old advice
 * and composed a "CALL_CENTER_AGENT" at runtime, or accounts hold one of the
 * three names as a bare {@code user_roles} string (there is no foreign key to
 * {@code roles}), the migration FAILS — and, being transactional, leaves the
 * database exactly as it was, at V42.
 *
 * <p>Adopting would merge the operator's grants into an undeletable built-in and
 * hand device-security permissions to every account already holding the name,
 * none of them checked — at once, for any live token re-resolved per request.
 *
 * <p>Plain Flyway against its own container: the point is to stop at V42, seed
 * the collision, and only then apply V43, which a booted context cannot do.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
class V43CollisionFailsMigrationIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("v43_collision_it")
            .withUsername("user_svc")
            .withPassword("user_svc");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /** A fresh database in the shared container, migrated to V42. */
    private static PGSimpleDataSource freshAtV42() {
        String name = "db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(dataSource(POSTGRES.getDatabaseName())).execute("CREATE DATABASE " + name);
        PGSimpleDataSource ds = dataSource(name);
        flyway(ds, "42").migrate();
        return ds;
    }

    private static PGSimpleDataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private static Flyway flyway(PGSimpleDataSource ds, String target) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholders(Map.of("innbucks_country", "ZW"))
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static String currentVersion(PGSimpleDataSource ds) {
        return new JdbcTemplate(ds).queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    @Test
    void aSameNamedRoleFailsTheMigration_andNothingIsAdopted() {
        PGSimpleDataSource ds = freshAtV42();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // The operator's runtime role, composed under the old advice.
        jdbc.update("INSERT INTO roles (name, description, builtin, created_by) "
                + "VALUES ('CALL_CENTER_AGENT', 'Call centre', FALSE, 'ops@innbucks.co.zw')");
        jdbc.update("INSERT INTO role_permissions (role_name, permission_code) VALUES ('CALL_CENTER_AGENT', 'users:read')");

        assertThatThrownBy(() -> flyway(ds, "43").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V43: a role named CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR or FRAUD_DESK "
                        + "already exists");

        // Rolled back as a whole: still V42, the operator's role untouched and
        // not built in, and none of the three built-ins created.
        assertThat(currentVersion(ds)).isEqualTo("42");
        assertThat(jdbc.queryForObject("SELECT builtin FROM roles WHERE name = 'CALL_CENTER_AGENT'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE role_name = 'CALL_CENTER_AGENT'", String.class))
                .containsExactly("users:read");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE name IN ('CALL_CENTER_SUPERVISOR','FRAUD_DESK')", Long.class))
                .isZero();
    }

    @Test
    void anOrphanUserRolesStringFailsTheMigration() {
        PGSimpleDataSource ds = freshAtV42();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO users (id, first_name, last_name, phone_number, email, password, home_country, "
                + "active, approved, created_at) VALUES "
                + "(900, 'Rudo', 'Banda', '+263771900900', 'rudo.banda@innbucks.co.zw', 'x', 'ZW', true, true, NOW())");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (900, 'FRAUD_DESK')");

        assertThatThrownBy(() -> flyway(ds, "43").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V43: accounts already hold");

        assertThat(currentVersion(ds)).isEqualTo("42");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE name = 'FRAUD_DESK'", Long.class)).isZero();
    }

    @Test
    void aCleanDatabaseMigrates() {
        PGSimpleDataSource ds = freshAtV42();
        // A near-miss name is not a collision.
        new JdbcTemplate(ds).update("INSERT INTO roles (name, description, builtin) "
                + "VALUES ('CALL_CENTER_TEAM_LEAD', 'Team lead', FALSE)");

        flyway(ds, "43").migrate();

        assertThat(currentVersion(ds)).isEqualTo("43");
        assertThat(new JdbcTemplate(ds).queryForObject(
                "SELECT count(*) FROM roles WHERE builtin AND name IN "
                        + "('CALL_CENTER_AGENT','CALL_CENTER_SUPERVISOR','FRAUD_DESK')", Long.class))
                .isEqualTo(3L);
    }
}
