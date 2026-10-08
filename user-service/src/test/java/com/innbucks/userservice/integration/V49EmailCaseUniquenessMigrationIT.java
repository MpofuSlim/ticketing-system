package com.innbucks.userservice.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V49: {@code GClerkson@innbucks.co.zw} and {@code gclerkson@innbucks.co.zw}
 * never both exist (owner decision, 2026-10-08). A unique index on
 * {@code UPPER(email)} — the expression Spring Data's IgnoreCase lookups
 * compare — refuses the second spelling at the database, whatever path writes
 * it. A table that already holds a letter-case pair FAILS the migration (and,
 * being transactional, stays at V48): which account keeps the address is an
 * operator's decision.
 *
 * <p>Plain Flyway against its own container, like {@code V43CollisionFailsMigrationIT}:
 * the point is to stop at V48, seed the data, and only then apply V49.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
class V49EmailCaseUniquenessMigrationIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("v49_email_it")
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

    /** A fresh database in the shared container, migrated to V48. */
    private static PGSimpleDataSource freshAtV48() {
        String name = "db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(dataSource(POSTGRES.getDatabaseName())).execute("CREATE DATABASE " + name);
        PGSimpleDataSource ds = dataSource(name);
        flyway(ds, "48").migrate();
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

    private static void insertUser(JdbcTemplate jdbc, long id, String email, String phone) {
        jdbc.update("INSERT INTO users (id, first_name, last_name, phone_number, email, password, home_country, "
                + "active, approved, created_at) VALUES (?, 'Grace', 'Clerkson', ?, ?, 'x', 'ZW', true, true, "
                + "(now() AT TIME ZONE 'UTC'))", id, phone, email);
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'users' AND indexname = ?)",
                Boolean.class, name));
    }

    @Test
    void afterV49_aLetterCaseVariantOfAHeldAddressIsRefused_byTheDatabase() {
        PGSimpleDataSource ds = freshAtV48();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        insertUser(jdbc, 901, "gclerkson@innbucks.co.zw", "+263771901901");
        // Accounts with no email: any number of them, before and after.
        insertUser(jdbc, 902, null, "+263771902902");
        insertUser(jdbc, 903, null, "+263771903903");

        flyway(ds, "49").migrate();

        assertThat(currentVersion(ds)).isEqualTo("49");
        assertThat(indexExists(jdbc, "uq_users_email_upper")).isTrue();
        assertThat(indexExists(jdbc, "idx_users_email_upper")).isFalse();
        assertThatThrownBy(() -> insertUser(jdbc, 904, "GClerkson@innbucks.co.zw", "+263771904904"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email_upper");
        insertUser(jdbc, 905, null, "+263771905905");
        insertUser(jdbc, 906, "someone.else@innbucks.co.zw", "+263771906906");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(5L);
    }

    @Test
    void anExistingLetterCasePairFailsTheMigration_andNothingChanges() {
        PGSimpleDataSource ds = freshAtV48();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // What the case-sensitive uk_users_email let through before V49.
        insertUser(jdbc, 911, "gclerkson@innbucks.co.zw", "+263771911911");
        insertUser(jdbc, 912, "GClerkson@innbucks.co.zw", "+263771912912");

        assertThatThrownBy(() -> flyway(ds, "49").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V49: 1 email address(es) are held by more than one account in different "
                        + "letter case");

        // Rolled back as a whole: still V48, both rows untouched, the old index still there.
        assertThat(currentVersion(ds)).isEqualTo("48");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE UPPER(email) = 'GCLERKSON@INNBUCKS.CO.ZW'",
                Long.class)).isEqualTo(2L);
        assertThat(indexExists(jdbc, "idx_users_email_upper")).isTrue();
        assertThat(indexExists(jdbc, "uq_users_email_upper")).isFalse();
    }

    @Test
    void theIgnoreCaseLookupStillUsesAnIndex() {
        PGSimpleDataSource ds = freshAtV48();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        flyway(ds, "49").migrate();
        // One connection: the planner setting must apply to the EXPLAIN. With an
        // empty table the planner would pick a sequential scan on cost alone.
        String plan = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                StringBuilder out = new StringBuilder();
                try (java.sql.ResultSet rs = st.executeQuery(
                        "EXPLAIN SELECT id FROM users WHERE UPPER(email) = UPPER('GClerkson@innbucks.co.zw')")) {
                    while (rs.next()) out.append(rs.getString(1)).append('\n');
                }
                return out.toString();
            }
        });
        assertThat(plan).contains("uq_users_email_upper");
    }
}
