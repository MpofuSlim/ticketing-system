package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.testsupport.SqlRecorder;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How many SQL statements the account-loading paths cost, over real Postgres
 * and the real HTTP surface (real sessions where the path is authenticated).
 *
 * <p>What it pins, and why each half matters:
 * <ul>
 *   <li>{@code User.roles} / {@code User.defaultServices} stay EAGER but carry a
 *       {@code @BatchSize(100)}: a LIST of accounts loads each collection with one
 *       {@code IN} query per 100 accounts instead of one query PER ACCOUNT. A
 *       regression back to the per-owner select fails the formula assertions.</li>
 *   <li>{@code CustomerProfile.user} / {@code TenantProfile.user} are LAZY: the
 *       listings that batch-load profiles no longer reload every business
 *       account (and both its collections) a second time.</li>
 *   <li>Every path that reads one of those relations still works with
 *       open-in-view off — a {@code LazyInitializationException} anywhere fails
 *       the request, and so the test.</li>
 * </ul>
 *
 * <p>Statements are recorded per THREAD by {@link SqlRecorder} (MockMvc
 * dispatches on the test thread), so an after-commit send from an earlier
 * request never lands in a count. Account-related statements are pinned
 * exactly; each path's total is pinned as a ceiling equal to today's count, so
 * an unrelated improvement never fails it but a regression does.
 *
 * <p>Before → after, measured on the change that introduced this test (n = the
 * accounts returned, b = the business accounts among them; listings exclude the
 * filter's two reads): {@code GET /admin/users} and {@code /merchants}
 * 2 + 2n + b → 4; the internal tenant lookup 2 + 3n → 4 (20 → 4 for six);
 * {@code GET /organizations/{id}/members} 20 → 10 for six members; a customer
 * refresh 19 → 18; a customer support search 47 → 45. Sign-in, the mint and
 * JwtFilter are unchanged (10 / 15 / 21 and 7 / 11).
 */
class UserAssociationFetchCountIT extends SupportItSupport {

    @Autowired TenantProfileRepository tenantProfiles;
    @Autowired JwtUtil jwtUtil;

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }

    /** The statements {@code call} issued on this thread. */
    private static List<String> record(Call call) throws Exception {
        SqlRecorder.start();
        try {
            call.run();
        } catch (Exception | AssertionError e) {
            SqlRecorder.stop();
            throw e;
        }
        return SqlRecorder.stop();
    }

    private static final Predicate<String> USER_ENTITY =
            s -> s.startsWith("select") && s.contains(" from users ") && s.contains("first_name");
    /** A load OF the collection — not a users query that mentions it in a subquery. */
    private static final Predicate<String> ROLES = s -> s.matches("(?s)select \\S+ from user_roles .*");
    private static final Predicate<String> SERVICES =
            s -> s.matches("(?s)select \\S+ from user_default_services .*");
    /** An account loaded BY ID — what an eager to-one does to resolve its target. */
    private static final Predicate<String> USER_BY_ID = s -> USER_ENTITY.test(s) && s.endsWith("where u1_0.id=?");
    private static final Predicate<String> TENANT_PROFILES = s -> s.contains(" from tenant_profiles ");
    private static final Predicate<String> CUSTOMER_PROFILES = s -> s.contains(" from customer_profiles ");

    private static long count(List<String> sql, Predicate<String> p) {
        return sql.stream().filter(p).count();
    }

    private static String describe(List<String> sql) {
        StringBuilder b = new StringBuilder(sql.size() + " statements:");
        for (String s : sql) {
            b.append("\n  ").append(s.length() > 180 ? s.substring(0, 110) + " … " + s.substring(s.length() - 70) : s);
        }
        return b.toString();
    }

    /**
     * The LAZY profile relation: a customer-profile read is never followed by a
     * load of its account by id. With the old EAGER mapping, a profile read in a
     * session that did not already hold the account issued exactly that. (A
     * users query by phone or email after a profile read is a different lookup,
     * and allowed.)
     */
    private static void assertNoAccountReloadAfterProfile(List<String> sql) {
        for (int i = 0; i + 1 < sql.size(); i++) {
            if (CUSTOMER_PROFILES.test(sql.get(i))) {
                assertThat(USER_BY_ID.test(sql.get(i + 1)))
                        .as("statement %d reloads the profile's account\n%s", i + 1, describe(sql)).isFalse();
            }
        }
    }

    /** Batches of 100: one collection query per started hundred accounts. */
    private static long batches(int accounts) {
        return Math.max(1, (accounts + 99) / 100);
    }

    private User business(String role) {
        User u = users.save(User.builder().firstName("Rumbi").lastName("Moyo")
                .email("biz-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.valueOf(role)))
                .defaultServices(new HashSet<>(List.of("ticketing")))
                .active(true).approved(true).business(true).build());
        tenantProfiles.save(TenantProfile.builder().user(u).businessName("Showtime " + unique()).build());
        return u;
    }

    private User enrolledSuperAdmin() {
        return users.save(User.builder().firstName("Platform").lastName("Owner")
                .email("owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.SUPER_ADMIN))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
    }

    // ---------------------------------------------------------------- listings

    @Test
    @DisplayName("GET /admin/users and /merchants: one users query, one IN query per 100 accounts per collection, one profile query")
    void adminListings() throws Exception {
        for (int i = 0; i < 3; i++) business("EVENT_ORGANIZER");
        for (int i = 0; i < 3; i++) business("MERCHANT_ADMIN");
        for (int i = 0; i < 3; i++) customer();
        String admin = session(enrolledSuperAdmin());

        for (String includeCustomers : List.of("false", "true")) {
            int[] n = new int[1];
            List<String> sql = record(() -> n[0] = data(mockMvc.perform(get("/admin/users")
                            .param("includeCustomers", includeCustomers).header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].roles").isArray())).size());
            assertThat(n[0]).as("the fixtures are listed").isGreaterThanOrEqualTo(includeCustomers.equals("true") ? 10 : 7);
            assertListing(sql, n[0], "GET /admin/users?includeCustomers=" + includeCustomers);
        }

        int[] n = new int[1];
        List<String> sql = record(() -> {
            JsonNode body = data(mockMvc.perform(get("/admin/users/merchants").header("Authorization", admin))
                    .andExpect(status().isOk()));
            n[0] = body.size();
            // Business details still ride along: the profile's (lazy) account is never needed for them.
            boolean anyBusinessDetails = false;
            for (JsonNode row : body) anyBusinessDetails |= row.at("/businessDetails/businessName").isTextual();
            assertThat(anyBusinessDetails).isTrue();
        });
        assertThat(n[0]).isGreaterThanOrEqualTo(6);
        assertListing(sql, n[0], "GET /admin/users/merchants");
    }

    /**
     * A listing of {@code n} accounts behind a real bearer: the filter's two
     * reads (revocation check, token-state projection), one users query, one
     * profile query, and one batched query per collection per 100 accounts.
     * Before the change it was 2 + 2 + 2n + one account reload per business
     * account.
     */
    private static void assertListing(List<String> sql, int n, String path) {
        assertThat(count(sql, USER_ENTITY)).as(path + " — users queries\n" + describe(sql)).isEqualTo(1);
        assertThat(count(sql, ROLES)).as(path + " — user_roles queries\n" + describe(sql)).isEqualTo(batches(n));
        assertThat(count(sql, SERVICES)).as(path + " — user_default_services queries\n" + describe(sql))
                .isEqualTo(batches(n));
        assertThat(count(sql, TENANT_PROFILES)).as(path + " — tenant_profiles queries\n" + describe(sql)).isEqualTo(1);
        assertThat((long) sql.size()).as(path + " — total\n" + describe(sql)).isEqualTo(4 + 2 * batches(n));
    }

    @Test
    @DisplayName("internal tenant lookup: the profiles' accounts are not reloaded — 4 statements for six businesses")
    void internalTenantLookup() throws Exception {
        List<User> businesses = new ArrayList<>();
        for (int i = 0; i < 6; i++) businesses.add(business("EVENT_ORGANIZER"));
        List<String> uuids = businesses.stream().map(u -> u.getUserUuid().toString()).toList();

        List<String> sql = record(() -> mockMvc.perform(post("/users/internal/tenants/lookup-by-uuid")
                        .header("X-Internal-Token", "it-internal-token-it-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userUuids", uuids))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].userUuid").isString())
                .andExpect(jsonPath("$.data[0].businessName").isString()));

        // Was 1 + 2·6 (collections per account) + 1 + 6 (each profile reloading its account).
        assertThat(count(sql, USER_ENTITY)).as(describe(sql)).isEqualTo(1);
        assertThat(count(sql, ROLES)).as(describe(sql)).isEqualTo(1);
        assertThat(count(sql, SERVICES)).as(describe(sql)).isEqualTo(1);
        assertThat(count(sql, TENANT_PROFILES)).as(describe(sql)).isEqualTo(1);
        assertThat(sql).as(describe(sql)).hasSize(4);
    }

    @Test
    @DisplayName("GET /organizations/{id}/members: the members' collections load in one query each (was one per member)")
    void organizationMembers() throws Exception {
        User owner = users.save(User.builder().firstName("Tariro").lastName("Moyo")
                .email("owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(new LinkedHashSet<>(List.of(User.Role.MERCHANT_ADMIN.name())))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
        Organization org = organizationRepository.save(Organization.builder()
                .id(UUID.randomUUID()).name("Moyo Foods " + unique()).createdByUserId(owner.getId()).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        for (int i = 0; i < 5; i++) {
            memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                    .userId(customer().getId()).role(OrganizationMember.Role.STAFF).build());
        }
        String bearer = session(owner);

        List<String> sql = record(() -> mockMvc.perform(get("/organizations/{id}/members", org.getId())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6)));

        // The caller (one account, its two collections), then the six members:
        // one users query + one batched query per collection. Was 3 + 1 + 2·6.
        assertThat(count(sql, USER_ENTITY)).as(describe(sql)).isEqualTo(2);
        assertThat(count(sql, ROLES)).as(describe(sql)).isEqualTo(2);
        assertThat(count(sql, SERVICES)).as(describe(sql)).isEqualTo(2);
        assertThat(sql).as(describe(sql)).hasSizeLessThanOrEqualTo(10);
    }

    // ------------------------------------------------------- sessions and mint

    @Test
    @DisplayName("sign-in and the token mint: one account read with its two collections; unchanged by this change")
    void signInAndMint() throws Exception {
        User agent = eligibleStaff("CALL_CENTER_AGENT", true);
        String[] mfaToken = new String[1];
        List<String> password = record(() -> mfaToken[0] = data(passwordStep(agent.getEmail())
                .andExpect(status().isOk())).at("/mfaToken").asText());
        assertThat(count(password, USER_ENTITY)).as(describe(password)).isEqualTo(1);
        assertThat(count(password, ROLES)).as(describe(password)).isEqualTo(1);
        assertThat(count(password, SERVICES)).as(describe(password)).isEqualTo(1);
        assertThat(password).as(describe(password)).hasSizeLessThanOrEqualTo(10);

        // The MFA step loads the account by id (collections joined) and mints in buildResponse.
        String[] refresh = new String[1];
        List<String> mfa = record(() -> {
            JsonNode session = data(mfaVerify(mfaToken[0]).andExpect(status().isOk()));
            assertThat(session.at("/permissions").size()).isPositive();
            refresh[0] = session.at("/refreshToken").asText();
        });
        assertThat(count(mfa, USER_ENTITY)).as(describe(mfa)).isEqualTo(1);
        assertThat(count(mfa, ROLES) + count(mfa, SERVICES)).as(describe(mfa)).isZero();
        assertThat(mfa).as(describe(mfa)).hasSizeLessThanOrEqualTo(15);

        // A customer signs in in one step; buildResponse reads the customer
        // profile, whose account is already the one in the persistence context.
        User shopper = customer();
        String[] customerRefresh = new String[1];
        List<String> customerLogin = record(() -> customerRefresh[0] = data(passwordStep(shopper.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())).at("/refreshToken").asText());
        assertThat(count(customerLogin, USER_ENTITY)).as(describe(customerLogin)).isEqualTo(1);
        assertThat(count(customerLogin, ROLES)).as(describe(customerLogin)).isEqualTo(1);
        assertThat(count(customerLogin, SERVICES)).as(describe(customerLogin)).isEqualTo(1);
        assertThat(count(customerLogin, CUSTOMER_PROFILES)).as(describe(customerLogin)).isEqualTo(1);
        assertThat(customerLogin).as(describe(customerLogin)).hasSizeLessThanOrEqualTo(21);

        // Refresh re-mints from the LIVE account through the same buildResponse.
        List<String> customerRotation = record(() -> mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + customerRefresh[0]).header("X-Device-Id", DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString()));
        assertThat(count(customerRotation, USER_ENTITY)).as(describe(customerRotation)).isEqualTo(1);
        assertThat(count(customerRotation, CUSTOMER_PROFILES)).as(describe(customerRotation)).isEqualTo(1);
        assertNoAccountReloadAfterProfile(customerRotation);
        assertThat(customerRotation).as(describe(customerRotation)).hasSizeLessThanOrEqualTo(18);

        List<String> staffRotation = record(() -> mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + refresh[0]).header("X-Device-Id", DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions").isArray()));
        assertThat(count(staffRotation, USER_ENTITY)).as(describe(staffRotation)).isEqualTo(1);
        assertThat(count(staffRotation, CUSTOMER_PROFILES)).as(describe(staffRotation)).isZero();
        assertThat(staffRotation).as(describe(staffRotation)).hasSizeLessThanOrEqualTo(16);
    }

    @Test
    @DisplayName("JwtFilter: a current token costs the token-state projection only; a perms-less staff token reads the account once")
    void jwtFilter() throws Exception {
        User shopper = customer();
        String customerToken = data(passwordStep(shopper.getEmail()).andExpect(status().isOk())).at("/token").asText();

        // An endpoint the security chain refuses, so the filter is all that reads.
        List<String> current = record(() -> mockMvc.perform(get("/admin/roles/permissions")
                .header("Authorization", "Bearer " + customerToken)).andExpect(status().isForbidden()));
        assertThat(count(current, s -> s.contains(" from users "))).as(describe(current)).isEqualTo(1);
        assertThat(count(current, USER_ENTITY) + count(current, ROLES) + count(current, SERVICES))
                .as(describe(current)).isZero();
        assertThat(current).as(describe(current)).hasSizeLessThanOrEqualTo(7);

        // The rolling-deploy bridge: a staff token with no perms claim. The filter
        // reads the account (outside any transaction) and hands it to
        // StaffMintFilter, which reads its roles — if they were lazy this would be
        // a swallowed LazyInitializationException and a 401 INVALID_TOKEN.
        User agent = eligibleStaff("CALL_CENTER_AGENT", true);
        String legacy = jwtUtil.generateToken(agent.getEmail(), agent.getRoles(), List.of(), 1, false,
                null, null, null, agent.getFirstName(), null, agent.getLastName(),
                users.findTokenStateById(agent.getId()).orElseThrow().version(), "Zimbabwe",
                agent.getUserUuid(), null, false);
        // The bridged token authorizes: a 200 from a permission-gated endpoint, not
        // a 401.
        search("Bearer " + legacy, "SEC-8F2KQ9").andExpect(status().isOk());
        // The filter's own account read: exactly one roles and one services query for it.
        List<String> filterOnly = record(() -> mockMvc.perform(get("/admin/roles/permissions")
                .header("Authorization", "Bearer " + legacy)).andReturn());
        assertThat(count(filterOnly, USER_ENTITY)).as(describe(filterOnly)).isEqualTo(1);
        assertThat(count(filterOnly, ROLES)).as(describe(filterOnly)).isEqualTo(1);
        assertThat(count(filterOnly, SERVICES)).as(describe(filterOnly)).isEqualTo(1);
        assertThat(filterOnly).as(describe(filterOnly)).hasSizeLessThanOrEqualTo(11);
    }

    // ------------------------------------------------------------- support

    @Test
    @DisplayName("support search: a merchant and an app customer — each account read once, the customer profile without its account")
    void supportSearch() throws Exception {
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));

        User merchant = lockedMerchant();
        List<String> merchantSearch = record(() -> search(agent, merchant.getPhoneNumber())
                .andExpect(status().isOk()));
        assertThat(merchantSearch).as(describe(merchantSearch)).hasSizeLessThanOrEqualTo(48);

        User shopper = customer();
        List<String> customerSearch = record(() -> search(agent, shopper.getPhoneNumber())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections.innbucksApp.data.phones[0].customerProfile.registrationTier")
                        .value(2)));
        assertThat(count(customerSearch, CUSTOMER_PROFILES)).as(describe(customerSearch)).isPositive();
        assertNoAccountReloadAfterProfile(customerSearch);
        assertThat(customerSearch).as(describe(customerSearch)).hasSizeLessThanOrEqualTo(45);
    }

    // ------------------------------------------- the lazy relations themselves

    @Test
    @DisplayName("a profile read outside a transaction holds an uninitialised account proxy that still answers getId, toString, equals and hashCode")
    void profilesHoldAnUninitialisedProxy() {
        User biz = business("MERCHANT_ADMIN");
        User shopper = customer();

        TenantProfile tenant = tenantProfiles.findByUserId(biz.getId()).orElseThrow();
        CustomerProfile customer = customerProfiles.findByUserId(shopper.getId()).orElseThrow();
        for (Object[] pair : List.of(new Object[]{tenant, tenant.getUser(), biz},
                new Object[]{customer, customer.getUser(), shopper})) {
            User proxy = (User) pair[1];
            User expected = (User) pair[2];
            // Lazy on the owning side, without bytecode enhancement: a proxy, not loaded.
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            // The id comes from the FK column — no session needed (the listings rely on this).
            assertThat(proxy.getId()).isEqualTo(expected.getId());
            // Lombok's toString/equals/hashCode leave the relation alone.
            assertThat(pair[0].toString()).doesNotContain("password");
            assertThat(pair[0]).isEqualTo(pair[0]);
            assertThat(pair[0].hashCode()).isEqualTo(pair[0].hashCode());
            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            // And the relation really is lazy: anything past the id needs a session.
            assertThatThrownBy(proxy::getEmail).isInstanceOf(LazyInitializationException.class);
        }
    }

    @Test
    @DisplayName("tiers 2 → 3 → 4 write through the lazily loaded account: names, email and the device land on the right row")
    void tierLadderWritesThroughTheLazyAccount() throws Exception {
        String msisdn = phone();
        User user = users.save(User.builder().firstName("Customer").lastName("Pending")
                .phoneNumber(msisdn).password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).build());
        customerProfiles.save(CustomerProfile.builder().user(user).registrationTier(1).phoneVerified(true)
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        String email = "ladder-" + unique() + "@example.com";

        mockMvc.perform(post("/auth/customer/register/tier2").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "Sarah", "middleName", "Tiffany", "lastName", "Moyo",
                                "dateOfBirth", "2001-01-01", "gender", "FEMALE", "msisdn", msisdn,
                                "nationalId", "5337888V72", "email", email,
                                "address", Map.of("street1", "P.O. Box 12345", "city", "Harare",
                                        "postCode", "00263", "country", "Zimbabwe")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.tier").value(2));
        User after2 = users.findById(user.getId()).orElseThrow();
        assertThat(after2.getFirstName()).isEqualTo("Sarah");
        assertThat(after2.getMiddleName()).isEqualTo("Tiffany");
        assertThat(after2.getLastName()).isEqualTo("Moyo");
        assertThat(after2.getEmail()).isEqualTo(email);
        // @DynamicUpdate + the in-place role set: the write did not touch the roles.
        assertThat(after2.getRoles()).containsExactly(User.Role.CUSTOMER.name());

        mockMvc.perform(post("/auth/customer/register/tier3").param("phoneNumber", msisdn)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("biometricsReference", "BIO-" + unique(),
                                "device", Map.of("deviceId", "handset-" + unique(), "platform", "ANDROID")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.phoneNumber").value(msisdn))
                .andExpect(jsonPath("$.data.tier").value(3));
        assertThat(count("SELECT count(*) FROM devices WHERE user_id = ?1", user.getId())).isEqualTo(1);

        mockMvc.perform(post("/auth/customer/register/tier4").param("phoneNumber", msisdn)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("idDocumentPath", "kyc/id.pdf",
                                "proofOfResidencePath", "kyc/por.pdf"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.phoneNumber").value(msisdn))
                .andExpect(jsonPath("$.data.verified").value(true));
    }
}
