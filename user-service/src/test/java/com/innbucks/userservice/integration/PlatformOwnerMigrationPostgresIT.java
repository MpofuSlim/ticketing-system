package com.innbucks.userservice.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins V52 (the seeded platform owner) against real Postgres.
 *
 * <p>Plain Flyway against its own container rather than a Spring context: each
 * case stops at V51 in a database of its own, seeds the pre-V52 world, and only
 * then applies V52 — which a booted context (migrate-to-latest) cannot do.
 *
 * <p>What it proves: the one SUPER_ADMIN becomes the owner and nobody else
 * does; {@code uq_users_platform_owner} refuses a second owner while admitting
 * any number of non-owners; the migration FAILS (and leaves the database at
 * V51) when two accounts hold SUPER_ADMIN; and a database with none stamps
 * nobody, leaving it to {@code DataInitializer}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
class PlatformOwnerMigrationPostgresIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platform_owner_it")
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

    /** A fresh database of its own, so each case migrates from nothing. */
    private static PGSimpleDataSource database(String name) {
        new JdbcTemplate(dataSource(POSTGRES.getDatabaseName())).execute("CREATE DATABASE " + name);
        return dataSource(name);
    }

    private static PGSimpleDataSource dataSource(String name) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name));
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

    private static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO users (id, first_name, last_name, phone_number, email, password, home_country, active, approved, created_at) VALUES
                 (1, 'Super',  'Admin',  '0000000000',    'admin@innbucks.co.zw',  'x', 'ZW', true, true, NOW()),
                 (2, 'Tariro', 'Moyo',   NULL,            'tariro.moyo@innbucks.co.zw', 'x', 'ZW', true, true, NOW()),
                 (3, 'Rudo',   'Chikwanha', '+263771000003', 'rudo@traders.co.zw', 'x', 'ZW', true, true, NOW())
                """);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role) VALUES
                 (1, 'SUPER_ADMIN'), (2, 'CALL_CENTER_SUPERVISOR'), (3, 'MERCHANT_ADMIN'), (3, 'CUSTOMER')
                """);
    }

    private static List<Long> owners(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT id FROM users WHERE platform_owner ORDER BY id", Long.class);
    }

    @Test
    void theOneSuperAdminBecomesTheOwner_andTheIndexAdmitsNoSecond() {
        PGSimpleDataSource ds = database("one_super_admin");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "51").migrate();
        seedAccounts(jdbc);

        flyway(ds, "52").migrate();

        assertThat(owners(jdbc)).containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE NOT platform_owner", Integer.class))
                .isEqualTo(2);
        // A second owner is refused by the database itself, whatever the application does.
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET platform_owner = TRUE WHERE id = 2"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_platform_owner");
        // ...and the application's guarded stamp matches nothing for another row.
        assertThat(jdbc.update("UPDATE users SET platform_owner = TRUE WHERE id = 2 "
                + "AND NOT EXISTS (SELECT 1 FROM users o WHERE o.platform_owner AND o.id <> 2)")).isZero();
        assertThat(owners(jdbc)).containsExactly(1L);
        // A new row defaults to not being the owner.
        jdbc.update("""
                INSERT INTO users (id, first_name, last_name, email, password, home_country, active, approved, created_at)
                VALUES (4, 'New', 'Staff', 'new.staff@innbucks.co.zw', 'x', 'ZW', true, true, NOW())
                """);
        assertThat(jdbc.queryForObject("SELECT platform_owner FROM users WHERE id = 4", Boolean.class)).isFalse();
    }

    @Test
    void twoSuperAdminsFailTheMigration_leavingTheDatabaseAtV51() {
        PGSimpleDataSource ds = database("two_super_admins");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "51").migrate();
        seedAccounts(jdbc);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (2, 'SUPER_ADMIN')");

        assertThatThrownBy(() -> flyway(ds, "52").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("2 accounts hold SUPER_ADMIN");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_name = 'users' AND column_name = 'platform_owner'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("51");
    }

    @Test
    void noSuperAdminStampsNobody() {
        PGSimpleDataSource ds = database("no_super_admin");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "51").migrate();
        jdbc.update("""
                INSERT INTO users (id, first_name, last_name, email, password, home_country, active, approved, created_at)
                VALUES (1, 'Chipo', 'Banda', 'chipo@example.com', 'x', 'ZW', true, true, NOW())
                """);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (1, 'CUSTOMER')");

        flyway(ds, "52").migrate();

        assertThat(owners(jdbc)).isEmpty();
    }
}
