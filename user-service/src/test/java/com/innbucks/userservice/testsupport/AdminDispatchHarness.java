package com.innbucks.userservice.testsupport;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.controller.AdminUserController;
import com.innbucks.userservice.controller.RoleAdminController;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.service.AccountSessionRevoker;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.DeviceTrustService;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.RoleAdminService;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.service.UserAdminService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The role and account administration surface through REAL dispatch —
 * standalone MockMvc with the real controllers, the real services, the real
 * {@link RoleGrantGuard} and the real {@link GlobalExceptionHandler} — over
 * mocked repositories. What a test asserts here is exactly the status and body
 * the console receives.
 *
 * <p>{@code @PreAuthorize} is Spring Security's and does not run in a standalone
 * setup; the no-escalation rules under test run inside the services, reading
 * the caller's LIVE roles from {@link #users} — never the authorities on the
 * request, which is the point.
 */
public class AdminDispatchHarness {

    public final UserRepository users = mock(UserRepository.class);
    public final RoleRepository roles = mock(RoleRepository.class);
    public final AuditService audit = mock(AuditService.class);
    public final TokenVersionPublisher publisher = mock(TokenVersionPublisher.class);
    public final InMemoryTokenVersionBumper bumper = new InMemoryTokenVersionBumper(publisher);
    public final RoleGrantGuard guard = new RoleGrantGuard(users, roles);
    /** Staff profiles (V44), map-backed: {@link #eligibleStaff} adds one. */
    public final com.innbucks.userservice.repository.StaffProfileRepository staffProfiles =
            mock(com.innbucks.userservice.repository.StaffProfileRepository.class);
    public final Map<Long, com.innbucks.userservice.entity.StaffProfile> profileRows = new LinkedHashMap<>();
    public final com.innbucks.userservice.repository.OrganizationMemberRepository members =
            mock(com.innbucks.userservice.repository.OrganizationMemberRepository.class);
    public final com.innbucks.userservice.repository.OrganizationRepository organizations =
            mock(com.innbucks.userservice.repository.OrganizationRepository.class);
    /** The real staff rules, over the mocks above and the test profile's two staff domains. */
    public final com.innbucks.userservice.service.StaffEligibility eligibility =
            StaffFixtures.eligibility(staffProfiles, guard, members, organizations, roles, users);
    public final RoleAdminService roleAdmin = new RoleAdminService(roles, audit, guard, bumper, eligibility);
    public final UserAdminService userAdmin;
    public final MfaService mfa;
    public final MockMvc mvc;

    /** The roles table: every built-in as the migrations seed it, plus whatever a test adds. */
    public final Map<String, Role> roleRows = new LinkedHashMap<>();

    public AdminDispatchHarness() {
        BuiltInRoleRows.GRANTS.keySet().forEach(n -> roleRows.put(n, BuiltInRoleRows.builtin(n)));
        when(roles.findAllByNameIn(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            if (names == null) return List.of();
            return names.stream().filter(roleRows::containsKey).map(roleRows::get).toList();
        });
        when(roles.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(roleRows.get((String) inv.getArgument(0))));
        when(roles.existsById(anyString())).thenAnswer(inv -> roleRows.containsKey((String) inv.getArgument(0)));
        when(roles.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            roleRows.put(r.getName(), r);
            return r;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(staffProfiles.findById(any())).thenAnswer(inv -> Optional.ofNullable(profileRows.get((Long) inv.getArgument(0))));
        when(staffProfiles.findAllByUserIdIn(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(profileRows::containsKey).map(profileRows::get).toList();
        });
        when(roles.findHolderIds(anyString())).thenAnswer(inv -> List.of());

        AccountSessionRevoker revoker = new AccountSessionRevoker(bumper, mock(RefreshTokenRepository.class),
                mock(DeviceTrustService.class), mock(OtpRepository.class));
        userAdmin = new UserAdminService(users, mock(PasswordEncoder.class), audit,
                mock(ApplicationEventPublisher.class), mock(TenantProfileRepository.class), bumper, revoker, roles,
                guard, eligibility);
        mfa = new MfaService(users, mock(MfaBackupCodeRepository.class), mock(PasswordEncoder.class),
                new MfaProperties(), bumper, guard);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RoleAdminController(roleAdmin),
                        new AdminUserController(users, mock(TenantProfileRepository.class), userAdmin, mfa))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Adds (or replaces) a row in the roles table. */
    public Role role(String name, String... permissions) {
        Role r = BuiltInRoleRows.custom(name, permissions);
        roleRows.put(name, r);
        return r;
    }

    /** An active account holding {@code roleNames}, findable by id and by email. */
    public User account(long id, String email, String... roleNames) {
        User u = User.builder().id(id).userUuid(UUID.randomUUID()).email(email).password("x")
                .roles(new LinkedHashSet<>(List.of(roleNames))).active(true).approved(true)
                .tokenVersion(3L).build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        // setActive reads its target under the row lock (lockById).
        when(users.lockById(id)).thenReturn(Optional.of(u));
        when(users.findByEmail(email)).thenReturn(Optional.of(u));
        return u;
    }

    /**
     * Makes {@code user} STAFF-ELIGIBLE (V44): its email proven and an accepted
     * staff profile — what an account created through {@code POST /admin/staff}
     * looks like once its invite is redeemed. Needed before a staff role can be
     * ADDED to it.
     */
    public User eligibleStaff(User user) {
        user.setEmailVerifiedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1));
        profileRows.put(user.getId(), com.innbucks.userservice.entity.StaffProfile.builder()
                .userId(user.getId())
                .createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(2))
                .inviteAcceptedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1))
                .build());
        return user;
    }

    /**
     * The request's principal. The authorities are what the caller's TOKEN would
     * carry; the services ignore them and read the account's live roles.
     */
    public static UsernamePasswordAuthenticationToken as(String email, String... tokenAuthorities) {
        return new UsernamePasswordAuthenticationToken(email, null,
                java.util.Arrays.stream(tokenAuthorities).map(SimpleGrantedAuthority::new).toList());
    }
}
