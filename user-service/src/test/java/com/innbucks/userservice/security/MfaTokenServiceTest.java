package com.innbucks.userservice.security;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.exception.AccountInactiveException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.repository.UserTokenState;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mfaToken is bound to the account's session epoch ({@code tv}) and
 * refused once that moves or the account is deactivated. Pure JUnit; the live
 * {@code (token_version, active)} read is a mocked projection.
 */
class MfaTokenServiceTest {

    private static final String SECRET = "test-test-test-test-test-test-test-test";

    private UserRepository users;
    private MfaTokenService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        MfaProperties props = new MfaProperties();
        service = new MfaTokenService(SECRET, props, users);
    }

    private void live(long version, boolean active) {
        when(users.findTokenStateById(55L)).thenReturn(Optional.of(new UserTokenState(version, active)));
    }

    @Test
    void issue_bindsTheTokenToTheLiveVersion_andVerifyAcceptsIt() {
        live(8L, true);
        String token = service.issue(55L, MfaTokenService.Purpose.LOGIN_MFA);

        MfaTokenService.Subject subject = service.verifySubject(token, MfaTokenService.Purpose.LOGIN_MFA);
        assertThat(subject.userId()).isEqualTo(55L);
        assertThat(subject.tokenVersion()).isEqualTo(8L);
        assertThat(service.verify(token, MfaTokenService.Purpose.LOGIN_MFA)).isEqualTo(55L);
    }

    @Test
    void anyBumpSinceIssue_endsThePendingChallenge() {
        // Deactivation, role change, password reset, admin MFA reset, a newer
        // login — or the successful verify that SPENT it. Same answer as an
        // expired token, verbatim.
        live(8L, true);
        String token = service.issue(55L, MfaTokenService.Purpose.LOGIN_MFA);
        live(9L, true);

        assertThatThrownBy(() -> service.verify(token, MfaTokenService.Purpose.LOGIN_MFA))
                .isInstanceOf(MfaTokenService.InvalidMfaTokenException.class)
                .hasMessage("mfaToken is invalid or expired");
    }

    @Test
    void aDeactivatedAccount_is401_notAnExpiredToken() {
        // Deactivated between the password step and this one. Checked before the
        // version, although a deactivation also bumps it, so the client learns
        // what actually happened.
        live(8L, true);
        String token = service.issue(55L, MfaTokenService.Purpose.ENROLLMENT);
        live(9L, false);

        assertThatThrownBy(() -> service.verify(token, MfaTokenService.Purpose.ENROLLMENT))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void aTokenWithNoVersionClaim_isRefusedLikeAStaleOne() {
        // Minted before the rule existed. Refusing costs at most one re-login
        // during rollout; accepting would leave an unbound token path.
        live(8L, true);
        String legacy = Jwts.builder()
                .subject("55")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .claim("purpose", "LOGIN_MFA")
                .claim("kind", "mfa")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> service.verify(legacy, MfaTokenService.Purpose.LOGIN_MFA))
                .isInstanceOf(MfaTokenService.InvalidMfaTokenException.class)
                .hasMessage("mfaToken is invalid or expired");
    }

    @Test
    void issuing_forADeactivatedAccount_isRefused() {
        live(8L, false);
        assertThatThrownBy(() -> service.issue(55L, MfaTokenService.Purpose.LOGIN_MFA))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void anUnknownAccount_isAnInvalidToken() {
        when(users.findTokenStateById(55L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issue(55L, MfaTokenService.Purpose.LOGIN_MFA))
                .isInstanceOf(MfaTokenService.InvalidMfaTokenException.class);
    }

    @Test
    void thePurposeCheckIsUnchanged() {
        live(8L, true);
        String enrol = service.issue(55L, MfaTokenService.Purpose.ENROLLMENT);

        assertThatThrownBy(() -> service.verify(enrol, MfaTokenService.Purpose.LOGIN_MFA))
                .isInstanceOf(MfaTokenService.InvalidMfaTokenException.class)
                .hasMessage("mfaToken purpose mismatch");
    }
}
