package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.config.SalesAgentInviteProperties;
import com.innbucks.userservice.config.StaffProvisioningCheck;
import com.innbucks.userservice.dto.SalesAgentInviteDTOs;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.SalesAgentInvite;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.AccountSecurityAlertEvent;
import com.innbucks.userservice.event.SalesAgentInviteRequested;
import com.innbucks.userservice.exception.SalesAgentInviteException;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.SalesAgentInviteRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.SalesAgentInviteTokens;
import com.innbucks.userservice.testsupport.StaffFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sales-agent Foundry portal invites (V56): the internal mint and revoke, and
 * the public inspect / phone-code / accept rules. Pure Mockito with the real
 * {@link StaffEligibility} and staff-domain policy, so the reserved-domain rule
 * is the production one.
 */
class SalesAgentInviteServiceTest {

    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "tendai.moyo@gmail.com";
    private static final String CODE = "482913";
    private static final String PASSWORD = "Correct-Horse-7-Battery";
    private static final UUID TEAM_MEMBER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");
    private static final UUID ORGANIZER = UUID.fromString("8f14e45f-ceea-467f-a2b7-1c2d3e4f5a6b");
    private static final AuditContext CTX = new AuditContext("203.0.113.7", "portal");

    private SalesAgentInviteRepository invites;
    private UserRepository users;
    private CustomerProfileRepository customerProfiles;
    private RefreshTokenRepository refreshTokens;
    private StaffProfileRepository staffProfiles;
    private OtpService otp;
    private PasswordEncoder passwordEncoder;
    private TokenVersionBumper bumper;
    private AuditService audit;
    private ApplicationEventPublisher events;
    private StaffProvisioningCheck provisioning;
    private CellAffinityChecker cells;
    private SalesAgentInviteService service;

    private String token;
    private SalesAgentInvite invite;

    @BeforeEach
    void setUp() {
        invites = mock(SalesAgentInviteRepository.class);
        users = mock(UserRepository.class);
        customerProfiles = mock(CustomerProfileRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        staffProfiles = mock(StaffProfileRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        StaffEligibility eligibility = StaffFixtures.eligibility(staffProfiles, new RoleGrantGuard(users, roles),
                mock(OrganizationMemberRepository.class), mock(OrganizationRepository.class), roles, users);
        otp = mock(OtpService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("$argon2id$hashed");
        bumper = mock(TokenVersionBumper.class);
        when(bumper.bump(any())).thenReturn(8L);
        audit = mock(AuditService.class);
        events = mock(ApplicationEventPublisher.class);
        provisioning = mock(StaffProvisioningCheck.class);
        when(provisioning.invitesProvisioned()).thenReturn(true);
        cells = mock(CellAffinityChecker.class);
        when(cells.homeCountryFor(any())).thenReturn("ZW");
        SalesAgentInviteProperties properties = new SalesAgentInviteProperties();
        properties.afterPropertiesSet();
        service = new SalesAgentInviteService(invites, users, customerProfiles, refreshTokens, eligibility, otp,
                passwordEncoder, bumper, audit, events, properties, provisioning, cells);

        when(users.findAllByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(900L);
            return u;
        });
        when(customerProfiles.saveAndFlush(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(otp.verifySalesAgentInviteCode(PHONE, CODE)).thenReturn(true);

        token = SalesAgentInviteTokens.generate();
        invite = liveInvite();
        when(invites.findByTokenHash(SalesAgentInviteTokens.hash(token))).thenReturn(Optional.of(invite));
        when(invites.consume(eq(SalesAgentInviteTokens.hash(token)), any(UUID.class), any())).thenReturn(1);
    }

    private static SalesAgentInvite liveInvite() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return SalesAgentInvite.builder().id(31L).tokenHash("h").teamMemberId(TEAM_MEMBER)
                .organizerUuid(ORGANIZER).organizerName("Harare Arena Events").phoneNumber(PHONE).email(EMAIL)
                .createdAt(now.minusHours(1)).expiresAt(now.plusHours(71)).build();
    }

    /** An account on the invite's phone, findable and lockable. */
    private User onPhone(String email, User.Role... roles) {
        User user = User.builder().id(42L).userUuid(UUID.randomUUID()).firstName("Customer").lastName("Pending")
                .phoneNumber(PHONE).email(email).password("$argon2id$old").roles(User.roleNames(roles))
                .active(true).approved(true).failedLoginAttempts(3).build();
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(users.lockById(42L)).thenReturn(Optional.of(user));
        when(users.findById(42L)).thenReturn(Optional.of(user));
        return user;
    }

    private SalesAgentInviteDTOs.AcceptRequest accept(String code) {
        return new SalesAgentInviteDTOs.AcceptRequest(token, code, PASSWORD);
    }

    private static String errorCode(Throwable t) {
        return ((StaffPolicyException) t).getErrorCode();
    }

    private void nothingWritten() {
        verify(users, never()).saveAndFlush(any());
        verify(bumper, never()).bump(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Internal: create and revoke
    // ------------------------------------------------------------------

    private static SalesAgentInviteDTOs.CreateRequest create(String phone, String email) {
        return new SalesAgentInviteDTOs.CreateRequest(TEAM_MEMBER.toString(), ORGANIZER.toString(),
                "Harare\nArena Events", phone, email);
    }

    @Test
    @DisplayName("create: stores only the hash, supersedes the team member's live invites, emails after commit")
    void create_storesAnInvite_andPublishesTheEmail() {
        when(invites.save(any(SalesAgentInvite.class))).thenAnswer(inv -> {
            SalesAgentInvite saved = inv.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        when(invites.revokeLive(eq(TEAM_MEMBER), any())).thenReturn(1);

        SalesAgentInviteDTOs.Created created = service.create(create("0771234567", " Tendai.Moyo@Gmail.com "));

        ArgumentCaptor<SalesAgentInvite> saved = ArgumentCaptor.forClass(SalesAgentInvite.class);
        verify(invites).save(saved.capture());
        SalesAgentInvite row = saved.getValue();
        assertThat(row.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(row.getEmail()).isEqualTo(EMAIL);
        assertThat(row.getOrganizerName()).isEqualTo("Harare Arena Events");
        assertThat(row.getTeamMemberId()).isEqualTo(TEAM_MEMBER);
        assertThat(row.getOrganizerUuid()).isEqualTo(ORGANIZER);
        assertThat(row.getTokenHash()).hasSize(64);
        assertThat(Duration.between(row.getCreatedAt(), row.getExpiresAt())).isEqualTo(Duration.ofHours(72));
        assertThat(created.expiresAt()).isEqualTo(row.getExpiresAt());
        verify(invites).revokeLive(eq(TEAM_MEMBER), any());

        ArgumentCaptor<SalesAgentInviteRequested> event = ArgumentCaptor.forClass(SalesAgentInviteRequested.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().inviteId()).isEqualTo(77L);
        assertThat(event.getValue().to()).isEqualTo(EMAIL);
        assertThat(SalesAgentInviteTokens.wellFormed(event.getValue().rawToken())).isTrue();
        assertThat(SalesAgentInviteTokens.hash(event.getValue().rawToken())).isEqualTo(row.getTokenHash());
        assertThat(event.getValue().toString()).doesNotContain(event.getValue().rawToken());
    }

    @Test
    @DisplayName("create: each refusal is its own 400, and nothing is stored or sent")
    void create_refusals() {
        assertThatThrownBy(() -> service.create(new SalesAgentInviteDTOs.CreateRequest("not-a-uuid",
                ORGANIZER.toString(), null, PHONE, EMAIL)))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> service.create(null))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> service.create(create("12345", EMAIL)))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invalid_phone"));
        for (String bad : new String[] {null, "", "tendai", "tendai@gmail", "a b@gmail.com", "x@y.z\u0000"}) {
            assertThatThrownBy(() -> service.create(create(PHONE, bad)))
                    .as(String.valueOf(bad))
                    .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invalid_email"));
        }
        assertThatThrownBy(() -> service.create(create(PHONE, "tariro.moyo@innbucks.co.zw")))
                .satisfies(t -> {
                    assertThat(errorCode(t)).isEqualTo("email_domain_reserved");
                    assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(invites, never()).save(any());
        verify(invites, never()).revokeLive(any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("create: a cell that cannot build or send the link answers 503 before storing anything")
    void create_unconfigured() {
        when(provisioning.invitesProvisioned()).thenReturn(false);
        assertThatThrownBy(() -> service.create(create(PHONE, EMAIL)))
                .satisfies(t -> {
                    assertThat(errorCode(t)).isEqualTo("sales_agent_invites_unconfigured");
                    assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(invites, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("create never looks an account up: the organizer must not learn who has one")
    void create_isNoAccountOracle() {
        when(invites.save(any(SalesAgentInvite.class))).thenAnswer(inv -> inv.getArgument(0));
        service.create(create(PHONE, EMAIL));
        verify(users, never()).findByPhoneNumber(any());
        verify(users, never()).findAllByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("revoke: counts the live links it ended; a malformed id is invalid_request")
    void revoke() {
        when(invites.revokeLive(eq(TEAM_MEMBER), any())).thenReturn(2);
        assertThat(service.revoke(TEAM_MEMBER.toString()).revoked()).isEqualTo(2);
        assertThatThrownBy(() -> service.revoke("4b1e2c3d"))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invalid_request"));
    }

    // ------------------------------------------------------------------
    // Public: inspect and phone-code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("inspect: the organizer, the email, the phone masked, the expiry")
    void inspect() {
        SalesAgentInviteDTOs.Inspection seen = service.inspect(token);
        assertThat(seen.organizerName()).isEqualTo("Harare Arena Events");
        assertThat(seen.email()).isEqualTo(EMAIL);
        assertThat(seen.maskedPhone()).isEqualTo("****4567");
        assertThat(seen.expiresAt()).isEqualTo(invite.getExpiresAt());
    }

    @Test
    @DisplayName("inspect: malformed, unknown, used, revoked and expired are one invite_invalid")
    void inspect_invalid() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertThatThrownBy(() -> service.inspect("STI-" + token.substring(4)))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        assertThatThrownBy(() -> service.inspect(SalesAgentInviteTokens.generate()))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        invite.setUsedAt(now);
        assertThatThrownBy(() -> service.inspect(token))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        invite.setUsedAt(null);
        invite.setRevokedAt(now);
        assertThatThrownBy(() -> service.inspect(token))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        invite.setRevokedAt(null);
        invite.setExpiresAt(now.minusSeconds(1));
        assertThatThrownBy(() -> service.inspect(token))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
    }

    @Test
    @DisplayName("phone-code: sends the invite's own code to the invite's phone")
    void phoneCode_sends() {
        onPhone(null, User.Role.CUSTOMER);
        service.sendPhoneCode(token);
        verify(otp).sendSalesAgentInviteCode(PHONE);
    }

    @Test
    @DisplayName("phone-code: a refusal that needs no code costs no SMS")
    void phoneCode_refusalsSendNothing() {
        onPhone("someone.else@gmail.com", User.Role.CUSTOMER);
        assertThatThrownBy(() -> service.sendPhoneCode(token))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("email_mismatch"));
        assertThatThrownBy(() -> service.sendPhoneCode("garbage"))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        verify(otp, never()).sendSalesAgentInviteCode(any());
    }

    // ------------------------------------------------------------------
    // Accept: no account on the phone
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accept, no account: creates a CUSTOMER + SALES_AGENT account like the app's first sign-in")
    void accept_createsAnAccount() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        SalesAgentInviteDTOs.Accepted accepted = service.accept(accept(CODE), CTX);

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(created.capture());
        User user = created.getValue();
        assertThat(user.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPassword()).isEqualTo("$argon2id$hashed");
        assertThat(user.getRoles()).containsExactlyInAnyOrder("CUSTOMER", "SALES_AGENT");
        assertThat(user.isActive()).isTrue();
        assertThat(user.isApproved()).isTrue();
        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.getHomeCountry()).isEqualTo("ZW");
        assertThat(user.getFirstName()).isEqualTo("Customer");
        assertThat(user.getLastName()).isEqualTo("Pending");

        ArgumentCaptor<CustomerProfile> profile = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(customerProfiles).saveAndFlush(profile.capture());
        assertThat(profile.getValue().getRegistrationTier()).isEqualTo(1);
        assertThat(profile.getValue().isPhoneVerified()).isTrue();
        assertThat(profile.getValue().getPhoneVerifiedAt()).isNotNull();

        // The consume named the account it created, in the same statement.
        verify(invites).consume(eq(SalesAgentInviteTokens.hash(token)), eq(user.getUserUuid()), any());
        assertThat(accepted.email()).isEqualTo(EMAIL);
        assertThat(accepted.next()).isEqualTo("SIGN_IN");
        verify(cells).requireDomesticMsisdn(PHONE);
        // A brand-new account had no session to end and no password to warn about.
        verify(bumper, never()).bump(any());
        verify(events, never()).publishEvent(any(AccountSecurityAlertEvent.class));
        verifyRequiredAudit(user, true);
    }

    @Test
    @DisplayName("accept, no account: an email another account holds (any case) is email_in_use, before the code")
    void accept_newAccount_emailInUse() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(User.builder().id(5L).build()));

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> {
                    assertThat(errorCode(t)).isEqualTo("email_in_use");
                    assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(otp, never()).verifySalesAgentInviteCode(any(), any());
        nothingWritten();
    }

    @Test
    @DisplayName("accept, no account: a lost create race is a 409 try_again, never a 500")
    void accept_newAccount_createRace() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_phone"));

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("try_again"));
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // Accept: an account on the phone
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accept, an app customer with no email: gets the email, the password and SALES_AGENT")
    void accept_appCustomer() {
        User customer = onPhone(null, User.Role.CUSTOMER);
        CustomerProfile profile = CustomerProfile.builder().user(customer).registrationTier(1).build();
        when(customerProfiles.findByUserId(42L)).thenReturn(Optional.of(profile));
        when(refreshTokens.revokeAllForUser(eq(42L), any())).thenReturn(2);

        SalesAgentInviteDTOs.Accepted accepted = service.accept(accept(CODE), CTX);

        assertThat(customer.getEmail()).isEqualTo(EMAIL);
        assertThat(customer.getPassword()).isEqualTo("$argon2id$hashed");
        assertThat(customer.getRoles()).containsExactlyInAnyOrder("CUSTOMER", "SALES_AGENT");
        assertThat(customer.getFailedLoginAttempts()).isZero();
        assertThat(profile.isPhoneVerified()).isTrue();
        assertThat(profile.getPhoneVerifiedAt()).isNotNull();
        verify(invites).consume(eq(SalesAgentInviteTokens.hash(token)), eq(customer.getUserUuid()), any());
        assertThat(accepted.email()).isEqualTo(EMAIL);

        // A password was replaced: every session ends and the person is told.
        verify(bumper).bump(customer);
        verify(refreshTokens).revokeAllForUser(eq(42L), any());
        ArgumentCaptor<AccountSecurityAlertEvent> alert = ArgumentCaptor.forClass(AccountSecurityAlertEvent.class);
        verify(events).publishEvent(alert.capture());
        assertThat(alert.getValue().type()).isEqualTo(AccountSecurityAlertEvent.Type.PASSWORD_CHANGED);
        assertThat(alert.getValue().phoneNumber()).isEqualTo(PHONE);
        verify(cells, never()).requireDomesticMsisdn(any());
        verifyRequiredAudit(customer, false);
    }

    @Test
    @DisplayName("accept, an organizer with the SAME email in another case: keeps it, gains CUSTOMER and a profile")
    void accept_organizerSameEmail() {
        User organizer = onPhone("Tendai.Moyo@Gmail.com", User.Role.EVENT_ORGANIZER);
        when(customerProfiles.findByUserId(42L)).thenReturn(Optional.empty());

        SalesAgentInviteDTOs.Accepted accepted = service.accept(accept(CODE), CTX);

        assertThat(organizer.getEmail()).isEqualTo("Tendai.Moyo@Gmail.com");
        assertThat(accepted.email()).isEqualTo("Tendai.Moyo@Gmail.com");
        assertThat(organizer.getRoles()).containsExactlyInAnyOrder("EVENT_ORGANIZER", "CUSTOMER", "SALES_AGENT");
        // The mint refuses a CUSTOMER without a profile, so one is created.
        ArgumentCaptor<CustomerProfile> profile = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(customerProfiles).saveAndFlush(profile.capture());
        assertThat(profile.getValue().getUser()).isSameAs(organizer);
        assertThat(profile.getValue().getRegistrationTier()).isEqualTo(1);
        // Its email was not written, so no uniqueness check was needed.
        verify(users, never()).findAllByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("accept, an account with a different email: email_mismatch, never an overwrite")
    void accept_emailMismatch() {
        User customer = onPhone("rudo@chikwanha.co.zw", User.Role.CUSTOMER);

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> {
                    assertThat(errorCode(t)).isEqualTo("email_mismatch");
                    assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(customer.getEmail()).isEqualTo("rudo@chikwanha.co.zw");
        assertThat(customer.getRoles()).containsExactly("CUSTOMER");
        verify(otp, never()).verifySalesAgentInviteCode(any(), any());
        nothingWritten();
    }

    @Test
    @DisplayName("accept, an account with no email, the email held by ANOTHER account: email_in_use")
    void accept_existing_emailInUse() {
        onPhone(null, User.Role.CUSTOMER);
        when(users.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(User.builder().id(7L).build()));

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("email_in_use"));
        nothingWritten();
    }

    @Test
    @DisplayName("accept: a staff-domain email (domains can change after the invite) is email_domain_reserved")
    void accept_reservedEmail() {
        invite.setEmail("tariro.moyo@innbucks.co.zw");
        onPhone(null, User.Role.CUSTOMER);

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("email_domain_reserved"));
        nothingWritten();
    }

    @Test
    @DisplayName("accept: staff, SUPER_ADMIN, shop staff, a custom role, no role, inactive, unapproved: account_not_eligible")
    void accept_ineligibleAccounts() {
        record Case(String name, User.Role[] roles, boolean active, boolean approved, boolean profiled) {}
        List<Case> cases = List.of(
                new Case("SUPER_ADMIN", new User.Role[] {User.Role.SUPER_ADMIN, User.Role.CUSTOMER}, true, true, false),
                new Case("call center", new User.Role[] {User.Role.CALL_CENTER_AGENT, User.Role.CUSTOMER}, true, true, false),
                new Case("shop staff", new User.Role[] {User.Role.SHOP_USER}, true, true, false),
                new Case("staff-profiled customer", new User.Role[] {User.Role.CUSTOMER}, true, true, true),
                new Case("no role", new User.Role[] {}, true, true, false),
                new Case("inactive", new User.Role[] {User.Role.CUSTOMER}, false, true, false),
                new Case("unapproved", new User.Role[] {User.Role.MERCHANT_ADMIN}, true, false, false));
        for (Case c : cases) {
            User account = onPhone(null, c.roles());
            account.setActive(c.active());
            account.setApproved(c.approved());
            when(staffProfiles.findById(42L)).thenReturn(c.profiled()
                    ? Optional.of(StaffProfile.builder().userId(42L).build()) : Optional.empty());
            assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                    .as(c.name())
                    .satisfies(t -> {
                        assertThat(errorCode(t)).isEqualTo("account_not_eligible");
                        assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        // A custom role, by name: it might be a staff role.
        User custom = onPhone(null, User.Role.CUSTOMER);
        custom.getRoles().add("DEV_READONLY");
        when(staffProfiles.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("account_not_eligible"));
        verify(otp, never()).verifySalesAgentInviteCode(any(), any());
        nothingWritten();
    }

    @Test
    @DisplayName("accept: a wrong code is 400 phone_code_invalid, after every other check, and changes nothing")
    void accept_wrongCode() {
        User customer = onPhone(null, User.Role.CUSTOMER);

        assertThatThrownBy(() -> service.accept(accept("000000"), CTX))
                .satisfies(t -> {
                    assertThat(errorCode(t)).isEqualTo("phone_code_invalid");
                    assertThat(((StaffPolicyException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        // The code is checked on the invite's phone, under its own key (OtpService).
        verify(otp).verifySalesAgentInviteCode(PHONE, "000000");
        assertThat(customer.getEmail()).isNull();
        assertThat(customer.getRoles()).containsExactly("CUSTOMER");
        nothingWritten();
    }

    @Test
    @DisplayName("accept: a spent link is invite_invalid and recorded as a replay, never the token")
    void accept_replay() {
        invite.setUsedAt(LocalDateTime.now(ZoneOffset.UTC));
        invite.setAccountUserUuid(UUID.randomUUID());

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordFailure(eq(AuditEventType.SALES_AGENT_INVITE_REPLAYED), any(), any(),
                eq(String.valueOf(invite.getAccountUserUuid())), eq(AuditService.TARGET_TYPE_USER), eq("used"),
                metadata.capture(), eq(CTX));
        assertThat(metadata.getValue().toString()).doesNotContain(token);
        verify(invites, never()).consume(any(), any(), any());
        verify(otp, never()).verifySalesAgentInviteCode(any(), any());
        nothingWritten();
    }

    @Test
    @DisplayName("accept: losing the consume to a concurrent accept is invite_invalid, before the code")
    void accept_lostConsume() {
        onPhone(null, User.Role.CUSTOMER);
        when(invites.consume(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo("invite_invalid"));
        verify(otp, never()).verifySalesAgentInviteCode(any(), any());
        nothingWritten();
    }

    @Test
    @DisplayName("accept: the audit is REQUIRED and last: its failure fails the accept")
    void accept_auditRequired() {
        onPhone(null, User.Role.CUSTOMER);
        doThrow(new com.innbucks.userservice.exception.AuditUnavailableException(new RuntimeException("db")))
                .when(audit).recordRequired(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.accept(accept(CODE), CTX))
                .isInstanceOf(com.innbucks.userservice.exception.AuditUnavailableException.class);
    }

    @SuppressWarnings("unchecked")
    private void verifyRequiredAudit(User user, boolean newAccount) {
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(eq(AuditEventType.SALES_AGENT_PORTAL_ACCESS_GRANTED),
                eq(String.valueOf(user.getUserUuid())), eq(AuditService.ACTOR_TYPE_USER),
                eq(String.valueOf(user.getUserUuid())), eq(AuditService.TARGET_TYPE_USER),
                metadata.capture(), eq(CTX));
        Map<String, Object> m = metadata.getValue();
        assertThat(m).containsEntry("newAccount", newAccount)
                .containsEntry("teamMemberId", TEAM_MEMBER.toString())
                .containsEntry("organizerUuid", ORGANIZER.toString())
                .containsEntry("inviteId", 31L)
                .containsEntry("email", "t****@gmail.com")
                .containsEntry("phone", "****4567");
        assertThat(m.toString()).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain(token);
    }
}
