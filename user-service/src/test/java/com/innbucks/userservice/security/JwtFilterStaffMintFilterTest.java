package com.innbucks.userservice.security;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.StaffEligibility;
import com.innbucks.userservice.service.StaffMintFilter;
import com.innbucks.userservice.service.TokenRevocationService;
import com.innbucks.userservice.testsupport.StaffFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The mint-time eligibility filter on JwtFilter's perms-less (pre-{@code perms})
 * token path (V44): the account is read ONLY for a token carrying staff
 * authority (a NAMED role name or a PLATFORM code) — never for a CUSTOMER or
 * TEAM_MEMBER — and in {@code enforce} an ineligible holder loses the PLATFORM
 * codes AND the NAMED role name on the very next request.
 */
class JwtFilterStaffMintFilterTest {

    private static final Map<String, Set<String>> GRANTS = Map.of(
            "PRODUCT_OFFICER", Set.of(PermissionCatalog.USERS_MERCHANTS_READ),
            "TEAM_MEMBER", Set.of(),
            "CUSTOMER", Set.of());

    private JwtUtil jwtUtil;
    private JwtFilter filter;
    private UserRepository users;
    private StaffEligibility eligibility;
    private StaffAccountProperties properties;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        TokenRevocationService revocation = mock(TokenRevocationService.class);
        when(revocation.isRevoked(anyString())).thenReturn(false);
        when(revocation.sessionState(anyString(), anyLong())).thenReturn(TokenRevocationService.SessionState.CURRENT);
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findAllByNameIn(any())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream().filter(GRANTS::containsKey)
                    .map(n -> Role.builder().name(n).description(n).builtin(true)
                            .permissions(new LinkedHashSet<>(GRANTS.get(n))).build())
                    .toList();
        });
        filter = new JwtFilter(jwtUtil, new PermissionResolver(roles), revocation, mock(CellAffinityChecker.class));
        users = mock(UserRepository.class);
        eligibility = mock(StaffEligibility.class);
        properties = StaffFixtures.properties();
        StaffMintFilter mintFilter = new StaffMintFilter(eligibility, properties);
        meters = new SimpleMeterRegistry();
        ReflectionTestUtils.invokeMethod(mintFilter, "setMeterRegistry", meters);
        ReflectionTestUtils.setField(filter, "staffMintFilter", mintFilter);
        ReflectionTestUtils.setField(filter, "userRepository", users);
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

    private double ineligibleCount() {
        return meters.find(StaffMintFilter.METRIC).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("a perms-less CUSTOMER or TEAM_MEMBER token costs no account read and never touches the metric")
    void businessTokens_noAccountRead() throws Exception {
        properties.setEligibilityEnforcement(StaffAccountProperties.Enforcement.ENFORCE);
        Authentication customer = authenticate(jwtUtil.generateToken("rudo@example.com", "CUSTOMER", 2, true));
        Authentication gate = authenticate(jwtUtil.generateToken("gate@harare-arena.co.zw", "TEAM_MEMBER", 1, true));
        assertThat(customer.getAuthorities()).extracting("authority").contains("ROLE_CUSTOMER");
        assertThat(gate.getAuthorities()).extracting("authority").contains("ROLE_TEAM_MEMBER");
        verifyNoInteractions(users, eligibility);
        assertThat(ineligibleCount()).isZero();
    }

    @Test
    @DisplayName("enforce: a perms-less PRODUCT_OFFICER token of an ineligible holder loses the PLATFORM code AND the role name")
    void enforce_stripsPlatformCodesAndNamedRole() throws Exception {
        User legacy = User.builder().id(4802L).email("po@gmail.com")
                .roles(new LinkedHashSet<>(List.of("PRODUCT_OFFICER"))).active(true).build();
        when(users.findByEmail("po@gmail.com")).thenReturn(Optional.of(legacy));
        when(eligibility.ineligibility(legacy)).thenReturn(Optional.of(StaffEligibility.Ineligibility.OFF_DOMAIN));
        properties.setEligibilityEnforcement(StaffAccountProperties.Enforcement.ENFORCE);

        Authentication auth = authenticate(jwtUtil.generateToken("po@gmail.com", "PRODUCT_OFFICER", 4, true));

        assertThat(auth.getAuthorities()).extracting("authority")
                .doesNotContain("ROLE_PRODUCT_OFFICER", PermissionCatalog.USERS_MERCHANTS_READ);
        assertThat(meters.counter(StaffMintFilter.METRIC, "reason", "off_domain").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("watch: the same token keeps its authority, and the account WAS read (the staff branch)")
    void watch_keepsAuthority_butReadsTheAccount() throws Exception {
        User legacy = User.builder().id(4802L).email("po@gmail.com")
                .roles(new LinkedHashSet<>(List.of("PRODUCT_OFFICER"))).active(true).build();
        when(users.findByEmail("po@gmail.com")).thenReturn(Optional.of(legacy));
        when(eligibility.ineligibility(legacy)).thenReturn(Optional.of(StaffEligibility.Ineligibility.OFF_DOMAIN));

        Authentication auth = authenticate(jwtUtil.generateToken("po@gmail.com", "PRODUCT_OFFICER", 4, true));

        assertThat(auth.getAuthorities()).extracting("authority")
                .contains("ROLE_PRODUCT_OFFICER", PermissionCatalog.USERS_MERCHANTS_READ);
        verify(users).findByEmail("po@gmail.com");
        verify(users, never()).findByPhoneNumber(any());
    }
}
