package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link User.Role} constant has a {@code builtin = TRUE} row — the
 * property {@code RoleAdminService} relies on when it refuses to delete or
 * rename a built-in, and the one {@code StaffRoles.NAMED} relies on to name
 * roles that exist.
 *
 * <p>Two halves. The first reads the migrations themselves and runs everywhere;
 * the second applies them to a real Postgres (CI, or anywhere Docker is
 * reachable) and reads the table.
 */
class BuiltInRoleSeedTest {

    private static final Path MIGRATIONS = Paths.get("src", "main", "resources", "db", "migration");
    /** A role row in a migration's {@code INSERT INTO roles}: {@code ('NAME', '…', TRUE…}. */
    private static final Pattern BUILTIN_ROW = Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'(?:[^']|'')*'\\s*,\\s*TRUE");

    private static Set<String> enumNames() {
        return Arrays.stream(User.Role.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the migrations seed a builtin = TRUE row for every User.Role constant")
    void migrationsSeedEveryConstant() throws IOException {
        Set<String> seeded = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(file);
                int at = sql.indexOf("INSERT INTO roles");
                while (at >= 0) {
                    // ";\n" ends the statement — a description may contain a bare ';'.
                    int end = sql.indexOf(";\n", at);
                    if (end < 0) end = sql.length();
                    Matcher m = BUILTIN_ROW.matcher(sql.substring(at, end));
                    while (m.find()) seeded.add(m.group(1));
                    at = sql.indexOf("INSERT INTO roles", end);
                }
            }
        }
        assertThat(seeded).containsAll(enumNames());
    }

    @Test
    @EnabledIf("com.innbucks.userservice.testsupport.PostgresIntegrationTestBase#isDockerAvailable")
    @DisplayName("against Postgres: every User.Role constant has a builtin = TRUE row after migrating")
    void everyConstantHasABuiltinRow() {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("builtin_seed").withUsername("user_svc").withPassword("user_svc")) {
            pg.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(pg.getJdbcUrl());
            ds.setUser(pg.getUsername());
            ds.setPassword(pg.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                    .placeholders(Map.of("innbucks_country", "ZW")).load().migrate();

            List<String> builtins = new JdbcTemplate(ds)
                    .queryForList("SELECT name FROM roles WHERE builtin = TRUE", String.class);
            assertThat(new TreeSet<>(builtins)).containsAll(enumNames());
        }
    }
}
