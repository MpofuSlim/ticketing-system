package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.FederationAssertionVerifier;
import com.innbucks.userservice.security.FederationAssertionVerifierTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins what {@code POST /auth/exchange} promises. A REAL verifier with a
 * generated keypair so the signature path is exercised, everything else
 * mocked. Pure Mockito — no Spring, no Docker.
 */
class FederatedLoginServiceTest {

    private static final String ISSUER = "innbucks-middleware";
    private static final String AUDIENCE = "innbucks-foundry";
    private static final String PHONE = "+263771234567";
    private static final AuditContext CTX = new AuditContext("10.0.0.1", "app/2.3.0");

    private static KeyPair keyPair;

    private FederationAssertionVerifier verifier;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private UserRepository userRepository;
    private CustomerProfileRepository profileRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;
    private LoyaltyServiceClient loyalty;
    private AuditService auditService;
    private CellAffinityChecker affinity;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        verifier = new FederationAssertionVerifier(
                FederationAssertionVerifierTest.pem(keyPair.getPublic()), "", ISSUER, AUDIENCE, 300);
        verifier.parseKeys();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        profileRepository = mock(CustomerProfileRepository.class);
        when(profileRepository.save(any(CustomerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$argon2id$unusable");
        authService = mock(AuthService.class);
        when(authService.issueToken(any(User.class), any())).thenReturn(
                AuthResponseDTO.builder().token("access").refreshToken("refresh").roles(List.of("CUSTOMER")).build());
        loyalty = mock(LoyaltyServiceClient.class);
        auditService = mock(AuditService.class);
        affinity = mock(CellAffinityChecker.class);
    }

    private FederatedLoginService service(boolean enabled) {
        return new FederatedLoginService(enabled, verifier, redis, userRepository, profileRepository,
                passwordEncoder, authService, loyalty, auditService, affinity, "ZW");
    }

    private static String assertion(String subject, String jti, long ttl) {
        return FederationAssertionVerifierTest.assertion(
                keyPair.getPrivate(), subject, ISSUER, AUDIENCE, Instant.now(), ttl, jti);
    }

    private static User customer(boolean active) {
        return User.builder()
                .id(7L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(active).approved(true)
                .password("x").build();
    }

    private static ResponseStatusException statusOf(Runnable call) {
        try {
            call.run();
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("expected a ResponseStatusException");
    }

    // ------------------------------------------------------------------
    // Feature gates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("disabled = 404, and nothing is verified, looked up or audited")
    void disabled_is404() {
        ResponseStatusException ex = statusOf(() -> service(false).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(redis, userRepository, authService, auditService);
    }

    @Test
    @DisplayName("enabled but no public key = 503, not a stream of 401s")
    void halfProvisioned_is503() {
        verifier = new FederationAssertionVerifier("", "", ISSUER, AUDIENCE, 300);
        verifier.parseKeys();

        ResponseStatusException ex = statusOf(() -> service(true).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(redis, userRepository, authService);
    }

    // ------------------------------------------------------------------
    // Refusals — all the same opaque 401
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a bad assertion is an opaque 401, audited as bad_assertion, before Redis or the DB are touched")
    void badAssertion_isOpaque401() {
        ResponseStatusException ex = statusOf(() -> service(true).exchange("not.a.jws", null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(FederatedLoginService.REJECTED_REASON);
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_FEDERATED_LOGIN_REJECTED), isNull(), any(),
                isNull(), any(), eq("bad_assertion"), any(), eq(CTX));
        verifyNoInteractions(redis, userRepository, authService);
    }

    @Test
    @DisplayName("a replayed jti is refused before any account work, with the same opaque 401")
    void replay_isRefused() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        ResponseStatusException ex = statusOf(() -> service(true).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(FederatedLoginService.REJECTED_REASON);
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_FEDERATED_LOGIN_REJECTED), eq(PHONE), any(),
                eq(PHONE), any(), eq("replay"), any(), eq(CTX));
        verifyNoInteractions(userRepository, authService);
    }

    @Test
    @DisplayName("if the replay guard cannot be consulted the login is refused 503 — never issued unchecked")
    void replayGuardDown_failsClosed() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        ResponseStatusException ex = statusOf(() -> service(true).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(userRepository, authService);
    }

    @Test
    @DisplayName("the jti is burned for at least the assertion's remaining lifetime plus the grace window")
    void jtiTtlCoversTheAssertionLifetime() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(customer(true)));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.of(CustomerProfile.builder().build()));

        service(true).exchange(assertion(PHONE, "j-ttl", 120), null, CTX);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).setIfAbsent(eq(FederatedLoginService.JTI_KEY_PREFIX + FederatedLoginService.sha256Hex("j-ttl")),
                eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isGreaterThanOrEqualTo(Duration.ofSeconds(120 - 5).plus(FederatedLoginService.JTI_GRACE));
    }

    @Test
    @DisplayName("a phone that belongs to a STAFF account is refused — a middleware login never becomes a staff session")
    void staffPhone_isRefused() {
        User merchantAdmin = User.builder().id(9L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).active(true).approved(true).password("x").build();
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(merchantAdmin));

        ResponseStatusException ex = statusOf(() -> service(true).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(FederatedLoginService.REJECTED_REASON);
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_FEDERATED_LOGIN_REJECTED), eq(PHONE), any(),
                eq(PHONE), any(), eq("not_a_customer"), any(), eq(CTX));
        verify(authService, never()).issueToken(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an inactive customer is refused with the same opaque 401")
    void inactiveCustomer_isRefused() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(customer(false)));

        ResponseStatusException ex = statusOf(() -> service(true).exchange(assertion(PHONE, "j1", 60), null, CTX));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_FEDERATED_LOGIN_REJECTED), eq(PHONE), any(),
                eq(PHONE), any(), eq("account_inactive"), any(), eq(CTX));
        verify(authService, never()).issueToken(any(), any());
    }

    // ------------------------------------------------------------------
    // Success paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("first sign-in creates a passwordless tier-1 CUSTOMER, stamps phoneVerified, tells loyalty, mints a login token")
    void newPhone_createsCustomerAndIssuesLogin() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        AuthResponseDTO response = service(true).exchange(assertion(PHONE, "j1", 60), "device-1", CTX);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(user.hasRole(User.Role.CUSTOMER)).isTrue();
        assertThat(user.getRoles()).containsExactly(User.Role.CUSTOMER.name());
        assertThat(user.isActive()).isTrue();
        assertThat(user.isApproved()).isTrue();
        assertThat(user.isMfaEnabled()).isFalse();
        assertThat(user.getHomeCountry()).isEqualTo("ZW");
        // No chosen password: the stored value is an encoder output, never a
        // plaintext this service could have logged or returned.
        assertThat(user.getPassword()).isEqualTo("$argon2id$unusable");
        verify(passwordEncoder).encode(anyString());

        ArgumentCaptor<CustomerProfile> profile = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(profileRepository).save(profile.capture());
        assertThat(profile.getValue().getRegistrationTier()).isEqualTo(1);
        assertThat(profile.getValue().isPhoneVerified()).isTrue();
        assertThat(profile.getValue().getPhoneVerifiedAt()).isNotNull();
        assertThat(profile.getValue().isVerified()).isFalse();

        verify(loyalty).promoteUserByPhone(PHONE);
        verify(authService).issueToken(user, "device-1");
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_FEDERATED_LOGIN_SUCCESS), eq(PHONE), any(),
                eq(PHONE), any(), eq(Map.of("newAccount", true)), eq(CTX));
        assertThat(response.getToken()).isEqualTo("access");
        assertThat(response.getRefreshToken()).isEqualTo("refresh");
    }

    @Test
    @DisplayName("a returning customer is signed in without a new row; the phone-proof window is refreshed")
    void existingCustomer_isSignedIn() {
        User existing = customer(true);
        CustomerProfile profile = CustomerProfile.builder().user(existing).phoneVerified(false).build();
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));

        service(true).exchange(assertion(PHONE, "j1", 60), null, CTX);

        verify(userRepository, never()).save(any());
        assertThat(profile.isPhoneVerified()).isTrue();
        assertThat(profile.getPhoneVerifiedAt()).isNotNull();
        verify(profileRepository).save(profile);
        verify(loyalty).promoteUserByPhone(PHONE);
        verify(authService).issueToken(existing, null);
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_FEDERATED_LOGIN_SUCCESS), eq(PHONE), any(),
                eq(PHONE), any(), eq(Map.of("newAccount", false)), eq(CTX));
    }

    @Test
    @DisplayName("the subject is canonicalised to E.164 before lookup — a local-format number finds the same customer")
    void subjectIsNormalisedBeforeLookup() {
        User existing = customer(true);
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(existing));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.of(CustomerProfile.builder().build()));

        service(true).exchange(assertion("0771234567", "j1", 60), null, CTX);

        verify(userRepository).findByPhoneNumber(PHONE);
        verify(affinity).requireDomesticMsisdn(PHONE);
        verify(loyalty).promoteUserByPhone(PHONE);
    }

    @Test
    @DisplayName("losing the create race to a concurrent first login re-reads the winner's row instead of failing")
    void createRace_reusesTheWinner() {
        User winner = customer(true);
        when(userRepository.findByPhoneNumber(PHONE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_phone_country"));

        AuthResponseDTO response = service(true).exchange(assertion(PHONE, "j1", 60), null, CTX);

        assertThat(response.getToken()).isEqualTo("access");
        verify(authService).issueToken(winner, null);
    }

    @Test
    @DisplayName("a loyalty outage never fails the login")
    void loyaltyOutage_doesNotFailLogin() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(customer(true)));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.of(CustomerProfile.builder().build()));
        when(loyalty.promoteUserByPhone(PHONE)).thenReturn(false);

        AuthResponseDTO response = service(true).exchange(assertion(PHONE, "j1", 60), null, CTX);

        assertThat(response.getToken()).isEqualTo("access");
    }
}
