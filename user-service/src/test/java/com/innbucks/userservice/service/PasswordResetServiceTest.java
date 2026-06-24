package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {

    private OtpService otpService;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRepository refreshTokenRepository;
    private AuditService auditService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        otpService = mock(OtpService.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        auditService = mock(AuditService.class);
        service = new PasswordResetService(otpService, userRepository, passwordEncoder,
                refreshTokenRepository, auditService);
    }

    @Test
    void requestReset_delegatesToOtpService() {
        service.requestReset("+263771234567");
        verify(otpService).sendPasswordResetOtp("+263771234567");
    }

    @Test
    void resetPassword_passwordsMismatch_throws_andNeverConsumesOtp() {
        assertThatThrownBy(() -> service.resetPassword("+263771234567", "123456",
                "newpass12", "different34", AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Passwords do not match");
        // Confirm-match is checked before the OTP is touched — the code isn't burned.
        verifyNoInteractions(otpService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_badOtp_throws_andDoesNotChangePassword() {
        when(otpService.verifyPasswordResetOtp("+263771234567", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword("+263771234567", "000000",
                "newpass12", "newpass12", AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Invalid or expired code");
        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void resetPassword_success_setsPassword_revokesRefresh_bumpsVersion_clearsLockout() {
        User user = User.builder()
                .id(5L).phoneNumber("+263771234567").password("old-hash")
                .tokenVersion(2L).failedLoginAttempts(4)
                .lockedUntil(Instant.now().plusSeconds(600))
                .build();
        when(otpService.verifyPasswordResetOtp("+263771234567", "123456")).thenReturn(true);
        when(userRepository.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("newpass12")).thenReturn("new-hash");

        service.resetPassword("+263771234567", "123456", "newpass12", "newpass12", AuditContext.none());

        assertThat(user.getPassword()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(refreshTokenRepository).revokeAllForUser(eq(5L), any());
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_PASSWORD_CHANGED),
                any(), any(), any(), any(), any(), any());
    }
}
