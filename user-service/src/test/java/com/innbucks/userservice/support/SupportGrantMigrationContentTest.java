package com.innbucks.userservice.support;

import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.security.StaffRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V46, read as text, against the rules every support grant migration follows
 * (design §3.3). The Postgres half — the migration applied to a fresh database
 * — is {@code CallCenterRoleGrantsIT} / {@code BuiltInRoleSeedTest}; this half
 * runs everywhere and names the rule a future edit broke.
 */
class SupportGrantMigrationContentTest {

    private static final Path V46 = Path.of("src/main/resources/db/migration/V46__support_console_grants.sql");

    private static final Set<String> CODES = Set.of("support-console:read", "support-console:manage",
            "support-console:mfa:reset", "support-staff-targets:manage");

    private static String sql() throws Exception {
        // Comments stripped: only statements count.
        return Files.readString(V46).replaceAll("(?m)--.*$", "");
    }

    @Test
    @DisplayName("the permissions rows are inserted BEFORE the grants (the foreign-key trap)")
    void permissionsFirst() throws Exception {
        String sql = sql();
        int permissions = sql.indexOf("INSERT INTO permissions");
        int grants = sql.indexOf("INSERT INTO role_permissions");
        assertThat(permissions).isPositive();
        assertThat(grants).isGreaterThan(permissions);
        for (String code : CODES) {
            assertThat(sql.substring(permissions, grants)).as(code).contains("'" + code + "'");
        }
    }

    @Test
    @DisplayName("a DO block asserts each target role exists with builtin = TRUE, before any insert")
    void assertsBuiltInTargets() throws Exception {
        String sql = sql();
        int doBlock = sql.indexOf("DO $$");
        assertThat(doBlock).isNotNegative().isLessThan(sql.indexOf("INSERT INTO"));
        String block = sql.substring(doBlock, sql.indexOf("END $$"));
        assertThat(block).contains("builtin = TRUE").contains("RAISE EXCEPTION")
                .contains("'CALL_CENTER_AGENT'").contains("'CALL_CENTER_SUPERVISOR'");
    }

    @Test
    @DisplayName("grants: exactly the design's — agent read+manage, supervisor those plus mfa:reset and staff targets")
    void exactGrants() throws Exception {
        Map<String, Set<String>> grants = grants();
        assertThat(grants).containsOnlyKeys("CALL_CENTER_AGENT", "CALL_CENTER_SUPERVISOR");
        assertThat(grants.get("CALL_CENTER_AGENT"))
                .containsExactlyInAnyOrder("support-console:read", "support-console:manage");
        assertThat(grants.get("CALL_CENTER_SUPERVISOR")).containsExactlyInAnyOrderElementsOf(CODES);
    }

    @Test
    @DisplayName("never SUPER_ADMIN, never a TENANT or business built-in, never a wildcard-reserved code")
    void neverTheForbiddenTargetsOrCodes() throws Exception {
        String sql = sql();
        assertThat(sql).doesNotContain("SUPER_ADMIN").doesNotContain("'*'");
        Map<String, Set<String>> grants = grants();
        for (String role : grants.keySet()) {
            assertThat(StaffRoles.isBusinessBuiltIn(role)).as(role).isFalse();
            assertThat(StaffRoles.isNamed(role)).as(role).isTrue();
        }
        for (String code : CODES) {
            assertThat(PermissionCatalog.isKnown(code)).as(code).isTrue();
            assertThat(PermissionCatalog.scopeOf(code)).as(code).isEqualTo(PermissionCatalog.Scope.PLATFORM);
            assertThat(PermissionCatalog.isReservedToWildcard(code)).as(code).isFalse();
        }
    }

    private static Map<String, Set<String>> grants() throws Exception {
        String sql = sql();
        String values = sql.substring(sql.indexOf("INSERT INTO role_permissions"));
        Matcher m = Pattern.compile("\\('([A-Z_]+)',\\s*'([a-z:-]+)'\\)").matcher(values);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        while (m.find()) out.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
        assertThat(out).as("parsed grants").isNotEmpty();
        return out;
    }

    @Test
    @DisplayName("V46 runs after V45 and is the newest migration this release adds")
    void numbering() throws Exception {
        List<String> names;
        try (var s = Files.list(Path.of("src/main/resources/db/migration"))) {
            names = s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("V45__") || n.startsWith("V46__"))
                    .sorted().toList();
        }
        assertThat(names).containsExactly("V45__support_access_log_and_actions.sql", "V46__support_console_grants.sql");
    }
}
