package com.innbucks.userservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code src/main} writer of {@code User.roles}, {@code User.email},
 * {@code organization_members} and {@code organization_products} (V44 §2.4),
 * each marked guarded or unable to reach a staff account, with the reason.
 *
 * <p>The staff rules hold only while every path that can hand an account
 * authority, a staff address or a business is either guarded or provably cannot
 * reach a staff account. This test fails the moment a NEW writer appears (or an
 * existing one grows another write), so whoever adds it has to decide which of
 * the two it is and write that down here. It is a source scan, deliberately
 * crude: a false alarm costs a line in the table below, a missed writer costs
 * the invariant.
 */
class RoleWriterInventoryTest {

    private static final Path MAIN = Path.of("src/main/java/com/innbucks/userservice");

    /** What counts as a write, by kind. */
    private static final Map<String, Pattern> WRITERS = new LinkedHashMap<>();

    static {
        WRITERS.put("new_user", Pattern.compile("User\\.builder\\(\\)"));
        WRITERS.put("set_roles", Pattern.compile("\\.setRoles\\("));
        WRITERS.put("mutate_roles",
                Pattern.compile("getRoles\\(\\)\\.(add|addAll|remove|removeAll|clear|retainAll|removeIf)\\("));
        WRITERS.put("roles_alias", Pattern.compile("=\\s*\\w+\\.getRoles\\(\\);"));
        WRITERS.put("set_email", Pattern.compile("\\.setEmail\\("));
        WRITERS.put("member_write", Pattern.compile(
                "OrganizationMember\\.builder\\(\\)|\\bmembers\\.(save|saveAll|delete|deleteAll)\\(|\\.setRole\\("));
        WRITERS.put("product_write", Pattern.compile(
                "OrganizationProduct\\.builder\\(\\)|\\bproducts\\.(save|saveAll|delete|deleteAll)\\("));
    }

    /** file (relative to the package root) → kind → count, and why each is safe. */
    private static final Map<String, Map<String, Integer>> EXPECTED = new TreeMap<>();
    private static final Map<String, String> REASONS = new TreeMap<>();

    private static void writer(String file, String reason, Object... kindCounts) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < kindCounts.length; i += 2) counts.put((String) kindCounts[i], (Integer) kindCounts[i + 1]);
        EXPECTED.put(file, counts);
        REASONS.put(file, reason);
    }

    static {
        writer("service/StaffAccountService.java",
                "GUARDED — POST /admin/staff: StaffEmailPolicy + role checks + profile; the only staff creator.",
                "new_user", 1);
        writer("controller/AdminUserController.java",
                "GUARDED — delegates to UserAdminService.setRoles (row locks, RoleGrantGuard, StaffEligibility).",
                "set_roles", 1);
        writer("service/UserAdminService.java",
                "GUARDED — setRoles mutates the alias after lockAllByNameIn, requireMayAssign, the profile "
                        + "invariant and requireEligibleForStaffGrant.",
                "roles_alias", 1);
        writer("service/ServiceRequestService.java",
                "GUARDED — approve: requireNotStaffAccount (409 staff_account_not_eligible) and, for a staff "
                        + "role, requireEligibleForStaffGrant; audited USER_ROLES_CHANGED.",
                "mutate_roles", 1);
        writer("service/AuthService.java",
                "GUARDED — register: non-empty roles refused, requireEmailNotReserved before the duplicate check; "
                        + "roles come from defaultServices (business roles only).",
                "new_user", 1);
        writer("service/ShopStaffService.java",
                "GUARDED — shop staff create: requireEmailNotReserved before the duplicate check; roles are "
                        + "SHOP_ADMIN/SHOP_USER (TENANT). The setEmail is on a CSV-row DTO, not a User.",
                "new_user", 1, "set_email", 1);
        writer("service/TeamMemberService.java",
                "GUARDED — requireEmailNotReserved before the duplicate check; role is TEAM_MEMBER only.",
                "new_user", 1);
        writer("service/CustomerService.java",
                "GUARDED — tier-2: requireEmailNotReserved first, then requireNotStaffAccount on the "
                        + "phone's account.",
                "set_email", 1);
        writer("service/FederatedLoginService.java",
                "UNREACHABLE — creates a CUSTOMER matched by PHONE; a profiled account has no sign-in phone, "
                        + "and legacy staff are covered by the §2.9 phone guards.",
                "new_user", 1);
        writer("service/OtpService.java",
                "UNREACHABLE — materializeOrRefreshLocalAccount creates a CUSTOMER matched by PHONE; see "
                        + "FederatedLoginService.",
                "new_user", 1);
        writer("config/DataInitializer.java",
                "UNREACHABLE — seeds/merges the bootstrap SUPER_ADMIN only (exempt from every staff rule); the "
                        + "alias is a read in adoptionRefusalReason.",
                "new_user", 1, "set_roles", 1, "roles_alias", 1);
        writer("security/MfaPolicy.java",
                "READ ONLY — the alias is read to evaluate the gate-operator exemption.",
                "roles_alias", 1);
        writer("service/OrganizationService.java",
                "GUARDED — createForOwner (registration/approval, business accounts only), addMember and "
                        + "changeRole (requireNotStaffAccount), removeMember (taking away is always allowed), "
                        + "grantProduct (approval path, requireNotStaffAccount upstream).",
                "member_write", 7, "product_write", 3);
    }

    @Test
    @DisplayName("no writer of roles, email, memberships or products is unaccounted for")
    void inventoryIsComplete() throws IOException {
        Map<String, Map<String, Integer>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Map<String, Integer> counts = new TreeMap<>();
                WRITERS.forEach((kind, pattern) -> {
                    Matcher m = pattern.matcher(source);
                    int n = 0;
                    while (m.find()) n++;
                    if (n > 0) counts.put(kind, n);
                });
                if (!counts.isEmpty()) found.put(MAIN.relativize(file).toString().replace('\\', '/'), counts);
            }
        }
        assertThat(found)
                .as("A writer of User.roles / User.email / organization_members / organization_products was added "
                        + "or removed. Decide whether it is GUARDED by the staff rules (StaffEligibility, "
                        + "RoleGrantGuard, StaffEmailPolicy) or UNREACHABLE for a staff account, then record it "
                        + "in EXPECTED with the reason.")
                .isEqualTo(EXPECTED);
        assertThat(REASONS.keySet()).isEqualTo(EXPECTED.keySet());
    }
}
