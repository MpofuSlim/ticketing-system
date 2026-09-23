package com.innbucks.userservice.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins V39's backfill against rows shaped like the ones already on staging.
 *
 * <p>Plain Flyway against its own container rather than a Spring context: the
 * point is to stop at V38, seed the pre-organization world, and only then
 * apply V39 — which a booted context (migrate-to-latest on startup) cannot do.
 *
 * <p>What it proves, one case each: an organizer's business profile becomes the
 * organization; a blank business name falls back to the person; a person with
 * no name and no email falls back to their phone; a dual-role account gets ONE
 * organization holding every product; service values are normalised and
 * unknown ones skipped; and neither a customer nor a SUPER_ADMIN gets an
 * organization — platform staff are not a business.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
class OrganizationBackfillPostgresIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("org_backfill_it")
            .withUsername("user_svc")
            .withPassword("user_svc");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSeedAndBackfill() {
        POSTGRES.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        flyway(ds, "38").migrate();
        seedPreOrganizationRows();
        flyway(ds, "39").migrate();
    }

    private static Flyway flyway(PGSimpleDataSource ds, String target) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholders(Map.of("innbucks_country", "ZW"))
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static void seedPreOrganizationRows() {
        jdbc.update("""
                INSERT INTO users (id, first_name, last_name, phone_number, email, password, home_country, active, approved, created_at) VALUES
                 (1, 'Tendai', 'Moyo',      '+263771000001', 'tendai@events.co.zw', 'x', 'ZW', true, true, NOW()),
                 (2, 'Rudo',   'Chikwanha', '+263771000002', 'rudo@traders.co.zw',  'x', 'ZW', true, true, NOW()),
                 (3, 'Chipo',  'Banda',     '+263771000003', 'chipo@example.com',   'x', 'ZW', true, true, NOW()),
                 (4, '',       '',          '+263771000004', NULL,                  'x', 'ZW', true, true, NOW()),
                 (5, 'Admin',  'Root',      '+263771000005', 'root@innbucks.co.zw', 'x', 'ZW', true, true, NOW()),
                 (6, 'Farai',  'Dube',      '+263771000006', 'farai@both.co.zw',    'x', 'ZW', true, true, NOW())
                """);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role) VALUES
                 (1, 'EVENT_ORGANIZER'), (2, 'MERCHANT_ADMIN'), (3, 'CUSTOMER'), (4, 'MERCHANT_ADMIN'),
                 (5, 'SUPER_ADMIN'), (6, 'EVENT_ORGANIZER'), (6, 'MERCHANT_ADMIN'), (6, 'CUSTOMER')
                """);
        jdbc.update("""
                INSERT INTO user_default_services (user_id, service) VALUES
                 (1, 'ticketing'), (2, 'loyalty'), (2, ' Marketplace '), (4, 'marketplace'), (4, 'events'),
                 (5, 'ticketing'), (5, 'loyalty'), (5, 'marketplace'), (6, 'ticketing'), (6, 'loyalty')
                """);
        jdbc.update("""
                INSERT INTO tenant_profiles (user_id, business_name, business_email, business_phone_number, business_address, registration_number) VALUES
                 (1, 'Harare Events', 'bookings@events.co.zw', '+263242000001', '1 Samora Machel Ave', 'REG-001'),
                 (2, '   ', NULL, NULL, '  ', NULL)
                """);
    }

    private static Map<String, Object> orgOwnedBy(long userId) {
        return jdbc.queryForMap("""
                SELECT o.name, o.contact_email, o.contact_phone, o.address, o.registration_number, o.status, m.role,
                       (SELECT string_agg(p.product, ',' ORDER BY p.product)
                          FROM organization_products p WHERE p.organization_id = o.id) AS products
                  FROM organizations o
                  JOIN organization_members m ON m.organization_id = o.id
                 WHERE m.user_id = ?
                """, userId);
    }

    @Test
    void organizerBusinessProfileBecomesTheOrganization() {
        Map<String, Object> org = orgOwnedBy(1);
        assertThat(org).containsEntry("name", "Harare Events")
                .containsEntry("contact_email", "bookings@events.co.zw")
                .containsEntry("contact_phone", "+263242000001")
                .containsEntry("address", "1 Samora Machel Ave")
                .containsEntry("registration_number", "REG-001")
                .containsEntry("status", "ACTIVE")
                .containsEntry("role", "OWNER")
                .containsEntry("products", "ticketing");
    }

    @Test
    void blankBusinessNameFallsBackToThePersonAndServicesAreNormalised() {
        Map<String, Object> org = orgOwnedBy(2);
        assertThat(org).containsEntry("name", "Rudo Chikwanha")
                .containsEntry("contact_email", "rudo@traders.co.zw")
                .containsEntry("address", null)
                .containsEntry("products", "loyalty,marketplace");
    }

    @Test
    void namelessEmaillessAccountFallsBackToPhoneAndUnknownServiceIsSkipped() {
        Map<String, Object> org = orgOwnedBy(4);
        assertThat(org).containsEntry("name", "+263771000004")
                .containsEntry("contact_email", null)
                .containsEntry("products", "marketplace");
    }

    @Test
    void dualRoleAccountGetsOneOrganizationWithEveryProduct() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_members WHERE user_id = 6", Integer.class)).isEqualTo(1);
        assertThat(orgOwnedBy(6)).containsEntry("products", "loyalty,ticketing");
    }

    @Test
    void customersAndPlatformStaffGetNoOrganization() {
        List<Long> members = jdbc.queryForList(
                "SELECT user_id FROM organization_members ORDER BY user_id", Long.class);
        assertThat(members).containsExactly(1L, 2L, 4L, 6L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class)).isEqualTo(4);
    }

    @Test
    void existingSessionsAndRequestsCarryNoOrganizationYet() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE column_name = 'organization_id' AND is_nullable = 'YES'
                   AND table_name IN ('refresh_tokens', 'service_requests')
                 ORDER BY table_name
                """, String.class)).containsExactly("refresh_tokens", "service_requests");
    }
}
