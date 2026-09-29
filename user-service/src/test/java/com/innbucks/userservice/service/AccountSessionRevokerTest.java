package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AccountSessionRevoker} — the one implementation of "deactivate and end
 * every session". Uses the REAL {@link TokenVersionBumper} over a mocked
 * repository so the atomic deactivate-and-bump statement is the one exercised.
 */
class AccountSessionRevokerTest {

    private UserRepository users;
    private TokenVersionPublisher publisher;
    private RefreshTokenRepository refreshTokens;
    private DeviceTrustService deviceTrust;
    private OtpRepository otps;
    private AccountSessionRevoker revoker;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        publisher = mock(TokenVersionPublisher.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        deviceTrust = mock(DeviceTrustService.class);
        otps = mock(OtpRepository.class);
        revoker = new AccountSessionRevoker(new TokenVersionBumper(users, publisher),
                refreshTokens, deviceTrust, otps);
    }

    private User staff() {
        return User.builder().id(4812L).userUuid(UUID.randomUUID())
                .email("tariro.moyo@innbucks.co.zw").phoneNumber("+263771234567")
                .active(true).tokenVersion(11L).build();
    }

    @Test
    void revokeAll_endsEverything_inOneTransaction() {
        User user = staff();
        when(users.deactivateAndIncrementTokenVersion(4812L)).thenReturn(12L);
        when(refreshTokens.revokeAllForUser(eq(4812L), any(Instant.class))).thenReturn(3);
        when(otps.deleteByPhoneNumber("tariro.moyo@innbucks.co.zw")).thenReturn(1);
        when(otps.deleteByPhoneNumber("+263771234567")).thenReturn(0);

        AccountSessionRevoker.Revocation r = revoker.revokeAll(user, "admin_deactivation");

        // 1. active=false + bump in ONE atomic statement; the entity mirrors it.
        assertThat(user.isActive()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(12L);
        // 2. every refresh family, 3. device trust, 4. live reset codes by BOTH keys.
        verify(refreshTokens).revokeAllForUser(eq(4812L), any(Instant.class));
        verify(deviceTrust).clearTrustForUser(4812L);
        verify(otps).deleteByPhoneNumber("tariro.moyo@innbucks.co.zw");
        verify(otps).deleteByPhoneNumber("+263771234567");
        // 5. the shared Redis only hears about it after commit.
        verify(publisher).publishAfterCommit(user.getUserUuid(), 12L);
        verify(publisher, never()).publish(any(), any(Long.class));
        assertThat(r).isEqualTo(new AccountSessionRevoker.Revocation(12L, 3, 1));
    }

    @Test
    void sweepOnReactivation_endsLeftovers_withoutTouchingActive() {
        // For an account deactivated before revokeAll existed: bump, revoke,
        // clear — the caller flips `active` itself.
        User user = staff();
        user.setActive(false);
        when(users.incrementTokenVersion(4812L)).thenReturn(12L);
        when(refreshTokens.revokeAllForUser(eq(4812L), any(Instant.class))).thenReturn(2);

        AccountSessionRevoker.Revocation r = revoker.sweepOnReactivation(user);

        assertThat(user.isActive()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(12L);
        verify(users, never()).deactivateAndIncrementTokenVersion(any());
        verify(refreshTokens).revokeAllForUser(eq(4812L), any(Instant.class));
        verify(deviceTrust).clearTrustForUser(4812L);
        verify(otps).deleteByPhoneNumber("tariro.moyo@innbucks.co.zw");
        verify(otps).deleteByPhoneNumber("+263771234567");
        verify(publisher).publishAfterCommit(user.getUserUuid(), 12L);
        assertThat(r).isEqualTo(new AccountSessionRevoker.Revocation(12L, 2, 0));
    }

    @Test
    void theVersionIsBumpedBeforeAnythingElse() {
        // The atomic UPDATE takes the row lock first, so a login racing the
        // deactivation is serialised behind it from the very first statement.
        User user = staff();
        when(users.deactivateAndIncrementTokenVersion(4812L)).thenReturn(12L);

        revoker.revokeAll(user, "admin_deactivation");

        InOrder order = inOrder(users, refreshTokens, deviceTrust, otps);
        order.verify(users).deactivateAndIncrementTokenVersion(4812L);
        order.verify(refreshTokens).revokeAllForUser(eq(4812L), any(Instant.class));
        order.verify(deviceTrust).clearTrustForUser(4812L);
        order.verify(otps, org.mockito.Mockito.times(2)).deleteByPhoneNumber(anyString());
    }

    @Test
    void anAccountWithNoEmail_onlyClearsThePhoneKey() {
        User user = staff();
        user.setEmail(null);
        when(users.deactivateAndIncrementTokenVersion(4812L)).thenReturn(12L);

        revoker.revokeAll(user, "team_member_disabled");

        verify(otps).deleteByPhoneNumber("+263771234567");
        verify(otps, org.mockito.Mockito.times(1)).deleteByPhoneNumber(anyString());
    }

    @Test
    void itAlwaysRunsInsideTheCallersTransaction() throws NoSuchMethodException {
        Transactional tx = AccountSessionRevoker.class
                .getMethod("revokeAll", User.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        Transactional sweep = AccountSessionRevoker.class
                .getMethod("sweepOnReactivation", User.class)
                .getAnnotation(Transactional.class);
        assertThat(sweep).isNotNull();
        assertThat(sweep.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
