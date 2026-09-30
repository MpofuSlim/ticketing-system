package com.innbucks.userservice.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.config.StaffProvisioningCheck;
import com.innbucks.userservice.controller.AdminOrganizationController;
import com.innbucks.userservice.controller.AdminServiceRequestController;
import com.innbucks.userservice.controller.AdminStaffController;
import com.innbucks.userservice.controller.AdminUserController;
import com.innbucks.userservice.controller.OrganizationController;
import com.innbucks.userservice.controller.RoleAdminController;
import com.innbucks.userservice.controller.StaffInviteController;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.StaffInviteRequested;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.AuditEventRepository;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationProductRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.repository.StaffInviteRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionResolver;
import com.innbucks.userservice.security.StaffEmailPolicy;
import com.innbucks.userservice.security.StaffInviteTokens;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.service.AccountSessionRevoker;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.DeviceTrustService;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.OrganizationService;
import com.innbucks.userservice.service.RoleAdminService;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.service.ServiceRequestService;
import com.innbucks.userservice.service.StaffAccountService;
import com.innbucks.userservice.service.StaffEligibility;
import com.innbucks.userservice.service.StaffInviteService;
import com.innbucks.userservice.service.UserAdminService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The staff-account surface (V44) through REAL dispatch: standalone MockMvc over
 * the real controllers, the real services, the real {@link RoleGrantGuard},
 * {@link StaffEligibility} and {@link StaffEmailPolicy} and the real
 * {@link GlobalExceptionHandler} — over MAP-BACKED repositories, so a test reads
 * back exactly what a request wrote. What a test asserts is the status and body
 * the console receives.
 *
 * <p>{@code @PreAuthorize} does not run in a standalone setup; the rules under
 * test run inside the services, reading the caller's LIVE roles from
 * {@link #userRows}. The published {@link StaffInviteRequested} events are
 * captured in {@link #invitesRequested} — the raw tokens a real mailer would
 * have emailed.
 */
public class StaffDispatchHarness {

    public static final String OWNER = "admin@innbucks.co.zw";

    public final Map<Long, User> userRows = new LinkedHashMap<>();
    public final Map<String, Role> roleRows = new LinkedHashMap<>();
    public final Map<Long, StaffProfile> profileRows = new LinkedHashMap<>();
    public final Map<Long, StaffInvite> inviteRows = new LinkedHashMap<>();
    public final List<OrganizationMember> memberRows = new ArrayList<>();
    public final Map<UUID, Organization> organizationRows = new LinkedHashMap<>();
    public final List<StaffInviteRequested> invitesRequested = new ArrayList<>();
    public final List<Object> events = new ArrayList<>();

    private final AtomicLong userIds = new AtomicLong(4800);
    private final AtomicLong inviteIds = new AtomicLong(900);

    public final UserRepository users = mock(UserRepository.class);
    public final RoleRepository roles = mock(RoleRepository.class);
    public final StaffProfileRepository profiles = mock(StaffProfileRepository.class);
    public final StaffInviteRepository invites = mock(StaffInviteRepository.class);
    public final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    public final OrganizationRepository organizations = mock(OrganizationRepository.class);
    public final AuditService audit = mock(AuditService.class);
    public final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
    public final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    public final DeviceTrustService deviceTrust = mock(DeviceTrustService.class);
    public final OtpRepository otps = mock(OtpRepository.class);
    public final MfaBackupCodeRepository backupCodes = mock(MfaBackupCodeRepository.class);
    public final EmailNotificationClient email = mock(EmailNotificationClient.class);
    public final TokenVersionPublisher publisher = mock(TokenVersionPublisher.class);
    public final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    public final ServiceRequestRepository serviceRequests = mock(ServiceRequestRepository.class);

    public final InMemoryTokenVersionBumper bumper = new InMemoryTokenVersionBumper(publisher);
    public final StaffAccountProperties properties = StaffFixtures.properties();
    public final StaffEmailPolicy emailPolicy = new StaffEmailPolicy(properties);
    public final RoleGrantGuard guard = new RoleGrantGuard(users, roles);
    public final StaffEligibility eligibility =
            new StaffEligibility(profiles, emailPolicy, guard, members, organizations, roles, users);
    public final PasswordEncoder passwordEncoder = new PrefixPasswordEncoder();
    public final AccountSessionRevoker revoker = new AccountSessionRevoker(bumper, refreshTokens, deviceTrust, otps);
    public final StaffAccountService staffAccounts;
    public final StaffInviteService staffInvites;
    public final UserAdminService userAdmin;
    public final RoleAdminService roleAdmin;
    public final OrganizationService organizationService;
    public final ServiceRequestService serviceRequestService;
    public final MockMvc mvc;

    public StaffDispatchHarness() {
        guard.setAuditService(audit);
        revoker.setStaffInvites(invites);
        BuiltInRoleRows.GRANTS.keySet().forEach(n -> roleRows.put(n, BuiltInRoleRows.builtin(n)));
        stubRoles();
        stubUsers();
        stubProfiles();
        stubInvites();
        stubOrganizations();
        when(email.apiConfigured()).thenReturn(true);
        doAnswer(inv -> {
            Object event = inv.getArgument(0);
            events.add(event);
            if (event instanceof StaffInviteRequested requested) invitesRequested.add(requested);
            return null;
        }).when(eventPublisher).publishEvent(any(Object.class));

        StaffProvisioningCheck provisioning = new StaffProvisioningCheck(properties, email);
        PermissionResolver resolver = new PermissionResolver(roles);
        staffAccounts = new StaffAccountService(users, profiles, invites, roles, guard, eligibility, emailPolicy,
                properties, provisioning, resolver, passwordEncoder, revoker, bumper, refreshTokens, backupCodes,
                deviceTrust, audit, auditEvents, eventPublisher, new ObjectMapper(), new MarketTimeZone("ZW"));
        staffInvites = new StaffInviteService(invites, profiles, users, eligibility, guard, passwordEncoder, bumper,
                refreshTokens, backupCodes, deviceTrust, audit);
        userAdmin = new UserAdminService(users, passwordEncoder, audit, eventPublisher,
                mock(TenantProfileRepository.class), bumper, revoker, roles, guard, eligibility);
        roleAdmin = new RoleAdminService(roles, audit, guard, bumper, eligibility);
        organizationService = new OrganizationService(organizations, members, mock(OrganizationProductRepository.class),
                users, audit, bumper, eligibility);
        serviceRequestService = new ServiceRequestService(serviceRequests, users, eventPublisher);
        ReflectionTestUtils.setField(serviceRequestService, "staffEligibility", eligibility);
        ReflectionTestUtils.setField(serviceRequestService, "auditService", audit);
        ReflectionTestUtils.setField(serviceRequestService, "roleGrantGuard", guard);
        MfaService mfa = new MfaService(users, backupCodes, passwordEncoder, new MfaProperties(), bumper, guard);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminStaffController(staffAccounts),
                        new StaffInviteController(staffInvites),
                        new AdminUserController(users, mock(TenantProfileRepository.class), userAdmin, mfa),
                        new RoleAdminController(roleAdmin),
                        new AdminOrganizationController(organizationService),
                        new OrganizationController(organizationService, users),
                        new AdminServiceRequestController(serviceRequestService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        account(OWNER, "SUPER_ADMIN");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** An active, approved account holding {@code roleNames}. */
    public User account(String email, String... roleNames) {
        User u = User.builder().userUuid(UUID.randomUUID()).firstName("First").lastName("Last")
                .email(email).password("{x}unusable").country("Zimbabwe")
                .roles(new LinkedHashSet<>(List.of(roleNames))).active(true).approved(true)
                .tokenVersion(3L).build();
        return store(u);
    }

    /** A staff account as POST /admin/staff + an accepted invite leave it. */
    public User eligibleStaff(String email, String... roleNames) {
        User u = account(email, roleNames);
        u.setEmailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        profileRows.put(u.getId(), StaffProfile.builder().userId(u.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(2))
                .inviteAcceptedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1)).build());
        return u;
    }

    /** Adds (or replaces) a role row. */
    public Role role(String name, String... permissions) {
        Role r = BuiltInRoleRows.custom(name, permissions);
        roleRows.put(name, r);
        return r;
    }

    /** An ACTIVE organization {@code user} belongs to as {@code role}. */
    public Organization organizationOf(User user, OrganizationMember.Role role) {
        Organization org = Organization.builder().id(UUID.randomUUID()).name("Business of " + user.getEmail())
                .status(Organization.Status.ACTIVE).createdByUserId(user.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        organizationRows.put(org.getId(), org);
        memberRows.add(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(user.getId()).role(role).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        return org;
    }

    /** The last raw token a real mailer would have emailed. */
    public String lastRawToken() {
        return invitesRequested.get(invitesRequested.size() - 1).rawToken();
    }

    /** The request's principal; the services read the account's live roles, never these authorities. */
    public static UsernamePasswordAuthenticationToken as(String email, String... tokenAuthorities) {
        return new UsernamePasswordAuthenticationToken(email, null,
                java.util.Arrays.stream(tokenAuthorities).map(SimpleGrantedAuthority::new).toList());
    }

    /** A session for {@code u} carrying its {@code userUuid} claim, as the JWT filter installs it. */
    public static UsernamePasswordAuthenticationToken asUser(User u, String... tokenAuthorities) {
        UsernamePasswordAuthenticationToken auth = as(u.getEmail(), tokenAuthorities);
        auth.setDetails(java.util.Map.of(com.innbucks.userservice.security.AuthDetailsKeys.USER_UUID,
                u.getUserUuid()));
        return auth;
    }

    public User store(User u) {
        if (u.getId() == null) u.setId(userIds.incrementAndGet());
        if (u.getUserUuid() == null) u.setUserUuid(UUID.randomUUID());
        if (u.getCreatedAt() == null) u.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRows.put(u.getId(), u);
        return u;
    }

    // ------------------------------------------------------------------
    // Map-backed repositories
    // ------------------------------------------------------------------

    private void stubRoles() {
        when(roles.findAllByNameIn(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            return names == null ? List.of() : names.stream().filter(roleRows::containsKey).map(roleRows::get).toList();
        });
        when(roles.lockAllByNameIn(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            return names.stream().filter(roleRows::containsKey).map(roleRows::get).toList();
        });
        when(roles.findAll()).thenAnswer(inv -> new ArrayList<>(roleRows.values()));
        when(roles.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(roleRows.get((String) inv.getArgument(0))));
        when(roles.existsById(anyString())).thenAnswer(inv -> roleRows.containsKey((String) inv.getArgument(0)));
        when(roles.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            roleRows.put(r.getName(), r);
            return r;
        });
        when(roles.findHolderIds(anyString())).thenAnswer(inv -> userRows.values().stream()
                .filter(u -> u.getRoles().contains((String) inv.getArgument(0))).map(User::getId).toList());
    }

    private void stubUsers() {
        when(users.findById(any())).thenAnswer(inv -> Optional.ofNullable(userRows.get((Long) inv.getArgument(0))));
        when(users.lockById(any())).thenAnswer(inv -> Optional.ofNullable(userRows.get((Long) inv.getArgument(0))));
        when(users.findByEmail(any())).thenAnswer(inv -> userRows.values().stream()
                .filter(u -> Objects.equals(u.getEmail(), inv.getArgument(0))).findFirst());
        when(users.findByPhoneNumber(any())).thenAnswer(inv -> userRows.values().stream()
                .filter(u -> u.getPhoneNumber() != null && u.getPhoneNumber().equals(inv.getArgument(0))).findFirst());
        when(users.findAllByEmailIgnoreCase(any())).thenAnswer(inv -> userRows.values().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(inv.getArgument(0))).toList());
        when(users.existsByEmailIgnoreCase(any())).thenAnswer(inv -> userRows.values().stream()
                .anyMatch(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(inv.getArgument(0))));
        when(users.findByUserUuid(any())).thenAnswer(inv -> userRows.values().stream()
                .filter(u -> u.getUserUuid().equals(inv.getArgument(0))).findFirst());
        when(users.save(any(User.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(users.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<User> out = new ArrayList<>();
            ids.forEach(id -> { if (userRows.containsKey(id)) out.add(userRows.get(id)); });
            return out;
        });
        when(users.findStaffCandidates(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            return userRows.values().stream()
                    .filter(u -> u.getRoles().stream().anyMatch(names::contains) || profileRows.containsKey(u.getId()))
                    .toList();
        });
    }

    private void stubProfiles() {
        when(profiles.findById(any())).thenAnswer(inv -> Optional.ofNullable(profileRows.get((Long) inv.getArgument(0))));
        when(profiles.save(any(StaffProfile.class))).thenAnswer(inv -> {
            StaffProfile p = inv.getArgument(0);
            profileRows.put(p.getUserId(), p);
            return p;
        });
        when(profiles.findAllByUserIdIn(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(profileRows::containsKey).map(profileRows::get).toList();
        });
        when(profiles.findAll()).thenAnswer(inv -> new ArrayList<>(profileRows.values()));
        when(profiles.countCreatedBySince(any(), any())).thenAnswer(inv -> {
            Collection<String> creators = inv.getArgument(0);
            LocalDateTime since = inv.getArgument(1);
            return profileRows.values().stream().filter(p -> !p.isAdopted() && p.getCreatedAt().isAfter(since))
                    .filter(p -> creators.contains(userRows.get(p.getUserId()).getCreatedBy()))
                    .count();
        });
    }

    private void stubInvites() {
        when(invites.save(any(StaffInvite.class))).thenAnswer(inv -> {
            StaffInvite i = inv.getArgument(0);
            if (i.getId() == null) i.setId(inviteIds.incrementAndGet());
            inviteRows.put(i.getId(), i);
            return i;
        });
        when(invites.findByTokenHash(anyString())).thenAnswer(inv -> inviteRows.values().stream()
                .filter(i -> i.getTokenHash().equals(inv.getArgument(0))).findFirst());
        when(invites.consume(anyString(), any())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            for (StaffInvite i : inviteRows.values()) {
                if (i.getTokenHash().equals(hash) && i.isLive(now)) {
                    i.setUsedAt(now);
                    return 1;
                }
            }
            return 0;
        });
        when(invites.revokeLive(anyLong(), anyString(), any())).thenAnswer(inv -> {
            int n = 0;
            for (StaffInvite i : inviteRows.values()) {
                if (i.getUserId().equals(inv.getArgument(0)) && i.getUsedAt() == null && i.getRevokedAt() == null) {
                    i.setRevokedAt(inv.getArgument(2));
                    i.setRevokedReason(inv.getArgument(1));
                    n++;
                }
            }
            return n;
        });
        when(invites.findLive(anyLong())).thenAnswer(inv -> inviteRows.values().stream()
                .filter(i -> i.getUserId().equals(inv.getArgument(0)) && i.getUsedAt() == null && i.getRevokedAt() == null)
                .sorted((a, b) -> b.getId().compareTo(a.getId())).toList());
        when(invites.countByUserIdAndCreatedAtAfter(anyLong(), any())).thenAnswer(inv -> inviteRows.values().stream()
                .filter(i -> i.getUserId().equals(inv.getArgument(0))
                        && i.getCreatedAt().isAfter(inv.getArgument(1))).count());
        when(invites.findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(anyLong(), any())).thenAnswer(inv ->
                inviteRows.values().stream()
                        .filter(i -> i.getUserId().equals(inv.getArgument(0))
                                && i.getCreatedAt().isAfter(inv.getArgument(1)))
                        .min(java.util.Comparator.comparing(StaffInvite::getCreatedAt)));
    }

    private void stubOrganizations() {
        when(members.findByUserId(any())).thenAnswer(inv -> memberRows.stream()
                .filter(m -> m.getUserId().equals(inv.getArgument(0))).toList());
        when(members.findByOrganizationIdAndUserId(any(), any())).thenAnswer(inv -> memberRows.stream()
                .filter(m -> m.getOrganizationId().equals(inv.getArgument(0)) && m.getUserId().equals(inv.getArgument(1)))
                .findFirst());
        when(members.findByOrganizationIdOrderByCreatedAtAsc(any())).thenAnswer(inv -> memberRows.stream()
                .filter(m -> m.getOrganizationId().equals(inv.getArgument(0))).toList());
        when(members.save(any(OrganizationMember.class))).thenAnswer(inv -> {
            OrganizationMember m = inv.getArgument(0);
            memberRows.removeIf(x -> x.getId().equals(m.getId()));
            memberRows.add(m);
            return m;
        });
        when(organizations.findById(any())).thenAnswer(inv -> Optional.ofNullable(organizationRows.get((UUID) inv.getArgument(0))));
        when(organizations.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            List<Organization> out = new ArrayList<>();
            ids.forEach(id -> { if (organizationRows.containsKey(id)) out.add(organizationRows.get(id)); });
            return out;
        });
        when(organizations.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            organizationRows.put(o.getId(), o);
            return o;
        });
    }

    /** Never used as a real hash: {@code {x}<raw>} so a test can read what was set. */
    public static final class PrefixPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence raw) {
            return "{x}" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return encoded != null && encoded.equals("{x}" + raw);
        }
    }

    /** The hash stored for a raw token — for asserting what the invite row holds. */
    public static String hash(String raw) {
        return StaffInviteTokens.hash(raw);
    }
}
