package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.cells.CellRegistry;
import com.innbucks.userservice.controller.AdminUserController;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.TokenRevocationService;
import com.innbucks.userservice.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The access token is parsed and signature-verified EXACTLY ONCE per request.
 *
 * <p>{@link JwtFilter} used to call a {@code JwtUtil.extractX(token)} helper per
 * claim, each re-parsing and re-verifying the token: 13 verifications for an
 * admin's {@code GET /admin/users}. It now verifies once through
 * {@link JwtUtil#parseClaims} and reads every claim — including the {@code perms}
 * claim and the pre-V35 back-fill's roles — from that result.
 *
 * <p>Driven through the REAL Spring Security chain ({@link SecurityConfig},
 * including the {@code hasAuthority('users:read')} {@code @PreAuthorize}) and
 * the real {@link AdminUserController}, over mocked repositories and session
 * state. The per-request {@code (token_version, active)} read is still made —
 * once — on every request. {@link JwtUtil} is a Mockito spy, and every
 * String-form reader goes through {@code parseClaims} on it.
 */
@SpringJUnitWebConfig(JwtFilterParseOnceTest.Ctx.class)
@TestPropertySource(properties = {
        "jwt.secret=test-test-test-test-test-test-test-test",
        "jwt.expiration=3600000",
        "jwt.refresh-expiration=86400000",
        "innbucks.country=ZW"
})
class JwtFilterParseOnceTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AdminUserController.class})
    static class Ctx {
        @Bean JwtUtil jwtUtil() { return Mockito.spy(new JwtUtil()); }
        @Bean RoleRepository roleRepository() { return mock(RoleRepository.class); }
        @Bean TokenRevocationService tokenRevocationService() { return mock(TokenRevocationService.class); }
        @Bean CellAffinityChecker cellAffinityChecker() {
            return new CellAffinityChecker("ZW", mock(CellRegistry.class));
        }
        @Bean JwtFilter jwtFilter(JwtUtil u, RoleRepository roles, TokenRevocationService revocation,
                                  CellAffinityChecker affinity) {
            return new JwtFilter(u, new PermissionResolver(roles), revocation, affinity);
        }
        @Bean MetricsScrapeAuthFilter metricsScrapeAuthFilter() { return new MetricsScrapeAuthFilter(""); }
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean TenantProfileRepository tenantProfileRepository() { return mock(TenantProfileRepository.class); }
        @Bean UserAdminService userAdminService() { return mock(UserAdminService.class); }
        @Bean MfaService mfaService() { return mock(MfaService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwtUtil;
    @Autowired RoleRepository roles;
    @Autowired TokenRevocationService revocation;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(roles, revocation);
        clearInvocations(jwtUtil);
        when(revocation.isRevoked(anyString())).thenReturn(false);
        when(revocation.sessionState(anyString(), anyLong()))
                .thenReturn(TokenRevocationService.SessionState.CURRENT);
        when(roles.findAllByNameIn(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            return names.stream()
                    .filter("SUPER_ADMIN"::equals)
                    .map(n -> Role.builder().name(n).description(n).builtin(true)
                            .permissions(new LinkedHashSet<>(Set.of(PermissionCatalog.WILDCARD)))
                            .build())
                    .toList();
        });
    }

    private String adminToken(List<String> perms, long tokenVersion, String phone) {
        UUID user = UUID.randomUUID();
        return jwtUtil.generateToken("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), perms, List.of(),
                4, true, phone, null, null, null, null, null, tokenVersion, null,
                user, null, false);
    }

    @Test
    void adminList_parsesTheTokenExactlyOnce() throws Exception {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 7L, null);
        clearInvocations(jwtUtil);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(jwtUtil, times(1)).parseClaims(token);
        verify(jwtUtil, times(1)).parseClaims(anyString());
        // The per-request (token_version, active) read still happens, with the
        // claim read from the one parse.
        verify(revocation, times(1)).sessionState("admin@innbucks.co.zw", 7L);
    }

    @Test
    void adminList_withAPrePermsToken_isBackfilled_andParsedOnce() throws Exception {
        // No perms claim: permissionsFor re-derives them from the roles claim.
        String legacy = jwtUtil.generateToken("admin@innbucks.co.zw", "SUPER_ADMIN", 4, true);
        clearInvocations(jwtUtil);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk());

        verify(jwtUtil, times(1)).parseClaims(anyString());
        verify(roles).findAllByNameIn(any());
    }

    @Test
    void aTokenWithoutTheReadPermission_isStill403() throws Exception {
        String token = adminToken(List.of("roles:read"), 1L, null);
        clearInvocations(jwtUtil);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void aDeactivatedAccount_isStillAccountDeactivated_withOneParse() throws Exception {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, null);
        when(revocation.sessionState(eq("admin@innbucks.co.zw"), anyLong()))
                .thenReturn(TokenRevocationService.SessionState.INACTIVE);
        clearInvocations(jwtUtil);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"code\":\"ACCOUNT_DEACTIVATED\","
                        + "\"message\":\"This account has been deactivated\",\"data\":null}"));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void aSupersededSession_isStillSessionSuperseded() throws Exception {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, null);
        when(revocation.sessionState(eq("admin@innbucks.co.zw"), anyLong()))
                .thenReturn(TokenRevocationService.SessionState.SUPERSEDED);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"code\":\"SESSION_SUPERSEDED\","
                        + "\"message\":\"This session has been ended by a newer login\",\"data\":null}"));
    }

    @Test
    void aRevokedToken_isStillTokenRevoked() throws Exception {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, null);
        when(revocation.isRevoked(token)).thenReturn(true);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}"));
    }

    @Test
    void aWrongCellToken_isStill409_withOneParse() throws Exception {
        // A Kenyan MSISDN mints homeCountry=KE; this cell is ZW.
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, "+254712345678");
        clearInvocations(jwtUtil);

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorCode\":\"wrong_cell\"")));

        verify(jwtUtil, times(1)).parseClaims(anyString());
    }

    @Test
    void aTamperedToken_isStillInvalidToken() throws Exception {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, null);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"code\":\"INVALID_TOKEN\",\"message\":\"Token is invalid or expired\",\"data\":null}"));
    }

    /** The hook is real: each String-form reader is a parse, and is counted. */
    @Test
    void theCounterSeesEveryStringFormParse() {
        String token = adminToken(List.of(PermissionCatalog.USERS_READ), 1L, null);
        clearInvocations(jwtUtil);
        jwtUtil.extractEmail(token);
        jwtUtil.extractPermissions(token);
        jwtUtil.extractTokenVersion(token);
        jwtUtil.isTokenValid(token);
        verify(jwtUtil, times(4)).parseClaims(token);
    }
}
