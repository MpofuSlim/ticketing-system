package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.service.TokenRevocationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rolling-deploy bridge: a token minted BEFORE the {@code perms} claim
 * existed must still authorize against a check that has been migrated from
 * {@code hasRole} to {@code hasAuthority}.
 *
 * <p>This is a regression test with a specific history. The first CI run on this
 * change was red with eight {@code AdminUserControllerTest} cases returning 403,
 * because their caller held {@code ROLE_SUPER_ADMIN} and nothing else — which is
 * exactly the shape of a token already in a user's browser when the new build
 * rolls out. Without the back-fill, every logged-in admin loses the whole admin
 * surface until their session turns over: a self-inflicted outage that is
 * invisible in a green test suite and shows up only during deployment.
 */
class JwtFilterPermissionBackfillTest {

    private JwtUtil jwtUtil;
    private JwtFilter filter;
    private RoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);

        TokenRevocationService revocation = mock(TokenRevocationService.class);
        when(revocation.isRevoked(anyString())).thenReturn(false);
        when(revocation.isTokenVersionCurrent(anyString(), anyLong())).thenReturn(true);

        roleRepository = mock(RoleRepository.class);
        when(roleRepository.findAllByNameIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream()
                    .filter("SUPER_ADMIN"::equals)
                    .map(n -> Role.builder().name(n).description(n).builtin(true)
                            .permissions(new LinkedHashSet<>(Set.of(PermissionCatalog.WILDCARD)))
                            .build())
                    .toList();
        });

        filter = new JwtFilter(jwtUtil, new PermissionResolver(roleRepository), revocation,
                mock(CellAffinityChecker.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticate(String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/users");
        req.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(req, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("a pre-V35 token (roles, no perms claim) still gets its permissions")
    void preV35Token_getsPermissionsBackfilled() {
        // The short overload mints without a perms claim — the same shape as a
        // token issued by the previous release.
        String legacy = jwtUtil.generateToken("admin@innbucks.co.zw", "SUPER_ADMIN", 4, true);

        Authentication auth = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> authenticate(legacy));

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority")
                .contains("ROLE_SUPER_ADMIN", PermissionCatalog.USERS_READ,
                        PermissionCatalog.ROLES_WRITE);
    }

    @Test
    @DisplayName("a token WITH a perms claim is trusted as-is — no repository read")
    void tokenWithClaim_isNotBackfilled() throws Exception {
        // The claim is the authority once it exists. Re-deriving on every
        // request would both cost a query per request forever and silently
        // widen a token whose permissions were deliberately minted narrower
        // than its roles now imply.
        String current = jwtUtil.generateToken("admin@innbucks.co.zw",
                List.of("SUPER_ADMIN"), List.of(PermissionCatalog.USERS_READ), List.of(),
                4, true, null, null, null, null, null, null, 0L, "Zimbabwe", null, null, false);

        Authentication auth = authenticate(current);

        assertThat(auth.getAuthorities()).extracting("authority")
                .contains("ROLE_SUPER_ADMIN", PermissionCatalog.USERS_READ)
                .doesNotContain(PermissionCatalog.ROLES_WRITE);
        verify(roleRepository, never()).findAllByNameIn(any());
    }

    @Test
    @DisplayName("the back-fill grants no more than the role actually carries")
    void backfillIsBoundedByTheRole() throws Exception {
        // A role the stub does not resolve (not SUPER_ADMIN) expands to nothing,
        // so the fallback can never invent capability — it is the same resolver
        // the mint path uses, against the same table.
        String legacy = jwtUtil.generateToken("shopper@innbucks.co.zw", "CUSTOMER", 1, true);

        Authentication auth = authenticate(legacy);

        assertThat(auth.getAuthorities()).extracting("authority")
                .contains("ROLE_CUSTOMER")
                .doesNotContain(PermissionCatalog.USERS_READ, PermissionCatalog.ROLES_WRITE);
    }
}
