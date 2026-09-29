package com.innbucks.userservice.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for the session-revocation ITs (deactivation ends sessions,
 * the mfaToken is bound to token_version, atomic bumps). Real Postgres through
 * {@link PostgresIntegrationTestBase} — skipped without Docker, run by CI's
 * {@code mvn verify} — and the real HTTP surface through MockMvc, so every
 * refusal is observed with the status and body a client would get.
 */
@AutoConfigureMockMvc
public abstract class SessionRevocationItSupport extends PostgresIntegrationTestBase {

    protected static final String PASSWORD = "Staff-Login-7q-Pass";
    protected static final String DEVICE = "portal-browser-1";
    /** A fixed, valid Base32 TOTP secret; stored encrypted by MfaSecretConverter. */
    protected static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected UserRepository users;
    @Autowired protected CustomerProfileRepository customerProfiles;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected PlatformTransactionManager transactionManager;
    @PersistenceContext protected EntityManager em;

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String phone() {
        return "+26377" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
    }

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** A platform staff account; {@code enrolled} gives it a working TOTP second factor. */
    protected User staff(String role, boolean enrolled) {
        return users.save(User.builder()
                .firstName("Tariro").lastName("Moyo")
                .email("staff-" + unique() + "@innbucks.co.zw")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.valueOf(role)))
                .active(true).approved(true)
                .mfaEnabled(enrolled)
                .mfaSecret(enrolled ? TOTP_SECRET : null)
                .build());
    }

    /** A tier-2 super-app customer — never challenged for a second factor. */
    protected User customer() {
        User user = users.save(User.builder()
                .firstName("Tendai").lastName("Dube")
                .email("customer-" + unique() + "@example.com")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.CUSTOMER))
                .active(true).approved(true)
                .build());
        customerProfiles.save(CustomerProfile.builder()
                .user(user).registrationTier(2).fullName("Tendai Dube").phoneVerified(true).build());
        return user;
    }

    protected static String totp(String secret) {
        try {
            long step = new dev.samstevens.totp.time.SystemTimeProvider().getTime() / 30;
            return new dev.samstevens.totp.code.DefaultCodeGenerator().generate(secret, step);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP for test", e);
        }
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).at("/data");
    }

    /** The password step, as the portal does it. */
    protected ResultActions passwordStep(String email) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("identifier", email, "password", PASSWORD))));
    }

    protected ResultActions mfaVerify(String mfaToken) throws Exception {
        return mockMvc.perform(post("/auth/login/mfa")
                .header("X-Device-Id", DEVICE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("mfaToken", mfaToken, "code", totp(TOTP_SECRET)))));
    }

    /** Full sign-in of an ENROLLED staff account: password, then the TOTP step. */
    protected JsonNode signIn(String email) throws Exception {
        String mfaToken = data(passwordStep(email).andExpect(status().isOk())).at("/mfaToken").asText();
        return data(mfaVerify(mfaToken).andExpect(status().isOk()));
    }

    /** The administrator acting — long enough to prove V42 (over the old 64-character limit). */
    protected static final String ADMIN_EMAIL =
            "platform.operations.administrator.for-session-revocation-tests@innbucks-operations.co.zw";

    /**
     * The administrator's own account. Deactivating a staff-role holder and
     * resetting anyone's 2FA need the caller to hold everything the target
     * holds, read from the caller's LIVE roles — so the acting administrator
     * must exist, as the platform owner every one of these tests models.
     */
    @org.junit.jupiter.api.BeforeEach
    void ensureAdministratorAccount() {
        if (users.findByEmail(ADMIN_EMAIL).isEmpty()) {
            users.save(User.builder()
                    .firstName("Platform").lastName("Operations")
                    .email(ADMIN_EMAIL)
                    .phoneNumber(phone())
                    .password(passwordEncoder.encode(PASSWORD))
                    .roles(User.roleNames(User.Role.SUPER_ADMIN))
                    .active(true).approved(true)
                    .build());
        }
    }

    protected static UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null,
                List.of(new SimpleGrantedAuthority("users:activation:write"),
                        new SimpleGrantedAuthority("users:mfa:reset")));
    }

    protected ResultActions deactivate(Long userId) throws Exception {
        return mockMvc.perform(put("/admin/users/{id}/active", userId)
                .with(authentication(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"));
    }

    protected long liveTokenVersion(Long userId) {
        return users.findTokenStateById(userId).orElseThrow().version();
    }

    protected long liveRefreshTokens(Long userId) {
        return em.createQuery("select count(r) from RefreshToken r where r.userId = :id and r.revokedAt is null",
                        Long.class)
                .setParameter("id", userId)
                .getSingleResult();
    }
}
