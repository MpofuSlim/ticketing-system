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
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private PasswordResetService service;

    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "user@example.com";

    @BeforeEach
    void setUp() {
        otpService = mock(OtpService.class);
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new PasswordResetService(otpService, userRepository, passwordEncoder,
                refreshTokenRepository, auditService, eventPublisher);
    }

    // ---- requestReset --------------------------------------------------------

    @Test
    void requestReset_byPhone_knownUser_sendsPhoneOtp() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(new User()));
        service.requestReset(PHONE, null);
        verify(otpService).sendPasswordResetOtpToPhone(PHONE);
        verify(otpService, never()).sendPasswordResetOtpToEmail(any());
    }

    @Test
    void requestReset_byEmail_knownUser_sendsEmailOtp() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(new User()));
        service.requestReset(null, EMAIL);
        verify(otpService).sendPasswordResetOtpToEmail(EMAIL);
        verify(otpService, never()).sendPasswordResetOtpToPhone(any());
    }

    @Test
    void requestReset_emailWinsWhenBothSupplied() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(new User()));
        service.requestReset(PHONE, EMAIL);
        verify(otpService).sendPasswordResetOtpToEmail(EMAIL);
        verify(otpService, never()).sendPasswordResetOtpToPhone(any());
    }

    @Test
    void requestReset_unknownIdentifier_isSilentNoOp() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        service.requestReset(PHONE, null);
        verifyNoInteractions(otpService);
    }

    @Test
    void requestReset_neitherProvided_throws() {
        assertThatThrownBy(() -> service.requestReset(null, "  "))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessageContaining("Provide a phone number or email");
    }

    // ---- resetPassword -------------------------------------------------------

    @Test
    void resetPassword_passwordsMismatch_throws_andNeverConsumesOtp() {
        assertThatThrownBy(() -> service.resetPassword(PHONE, null, "123456",
                "newpass12", "different34", AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Passwords do not match");
        verifyNoInteractions(otpService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_badOtp_throws_andDoesNotChangePassword() {
        when(otpService.verifyPasswordResetOtp(PHONE, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword(PHONE, null, "000000",
                "newpass12", "newpass12", AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Invalid or expired code");
        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(any(), any());
    }

    @Test
    void resetPassword_phonePath_success_setsPassword_revokes_bumps_unlocks() {
        User user = User.builder()
                .id(5L).phoneNumber(PHONE).password("old-hash")
                .tokenVersion(2L).failedLoginAttempts(4)
                .lockedUntil(Instant.now().plusSeconds(600))
                .build();
        when(otpService.verifyPasswordResetOtp(PHONE, "123456")).thenReturn(true);
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("newpass12")).thenReturn("new-hash");

        service.resetPassword(PHONE, null, "123456", "newpass12", "newpass12", AuditContext.none());

        assertThat(user.getPassword()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(refreshTokenRepository).revokeAllForUser(eq(5L), any());
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_PASSWORD_CHANGED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void resetPassword_emailPath_success_resolvesUserByEmail() {
        User user = User.builder().id(8L).email(EMAIL).password("old").tokenVersion(0L).build();
        when(otpService.verifyPasswordResetOtp(EMAIL, "123456")).thenReturn(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("newpass12")).thenReturn("new-hash");

        service.resetPassword(null, EMAIL, "123456", "newpass12", "newpass12", AuditContext.none());

        assertThat(user.getPassword()).isEqualTo("new-hash");
        verify(otpService).verifyPasswordResetOtp(EMAIL, "123456");   // OTP keyed by email
        verify(refreshTokenRepository).revokeAllForUser(eq(8L), any());
    }
}
