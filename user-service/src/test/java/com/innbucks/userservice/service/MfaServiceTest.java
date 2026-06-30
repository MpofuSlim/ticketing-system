package com.innbucks.userservice.service;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.entity.MfaBackupCode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.UserRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cover the TOTP, backup-code, disable, and admin-reset paths of MfaService.
 * Uses the real samstevens TOTP library to mint codes against the secret the
 * service issues, so the round trip is genuine, not mocked.
 */
class MfaServiceTest {

    private UserRepository userRepository;
    private MfaBackupCodeRepository backupCodeRepository;
    private PasswordEncoder passwordEncoder;
    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        backupCodeRepository = mock(MfaBackupCodeRepository.class);
        // Real bcrypt so backup-code matches are genuine, not stubbed.
        passwordEncoder = new BCryptPasswordEncoder();
        MfaProperties props = new MfaProperties();
        props.setIssuer("InnBucks");
        props.setBackupCodeCount(10);
        mfaService = new MfaService(userRepository, backupCodeRepository, passwordEncoder, props);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user(Long id) {
        return User.builder().id(id).email("alice@example.com").build();
    }

    private static String totpFor(String secret) {
        try {
            long step = new SystemTimeProvider().getTime() / 30;
            return new DefaultCodeGenerator(HashingAlgorithm.SHA1).generate(secret, step);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- enrolment -----------------------------------------------------------

    @Test
    void startEnrollment_persistsSecretAndReturnsQr() {
        User u = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        MfaService.EnrollmentStart start = mfaService.startEnrollment(1L);

        assertThat(start.secret()).matches("[A-Z2-7]+");           // base32
        assertThat(start.otpauthUri()).startsWith("otpauth://totp/")
                .contains("issuer=InnBucks");
        assertThat(start.qrPngBase64()).isNotBlank();
        // Secret persisted, but mfaEnabled stays false until /complete.
        assertThat(u.getMfaSecret()).isEqualTo(start.secret());
        assertThat(u.isMfaEnabled()).isFalse();
    }

    @Test
    void completeEnrollment_validCode_enablesMfa_andMintsTenBackupCodes() {
        User u = user(2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(u));
        MfaService.EnrollmentStart start = mfaService.startEnrollment(2L);

        List<String> backupCodes = mfaService.completeEnrollment(2L, totpFor(start.secret()));

        assertThat(u.isMfaEnabled()).isTrue();
        assertThat(backupCodes).hasSize(10);
        assertThat(backupCodes).allMatch(c -> c.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));
        verify(backupCodeRepository).deleteAllForUser(2L);
    }

    @Test
    void completeEnrollment_wrongCode_throws() {
        User u = user(3L);
        when(userRepository.findById(3L)).thenReturn(Optional.of(u));
        mfaService.startEnrollment(3L);

        assertThatThrownBy(() -> mfaService.completeEnrollment(3L, "000000"))
                .isInstanceOf(MfaService.MfaException.class)
                .hasMessageContaining("didn't match");
        assertThat(u.isMfaEnabled()).isFalse();
    }

    @Test
    void completeEnrollment_noPendingSecret_throws() {
        User u = user(4L); // never called startEnrollment → mfaSecret is null
        when(userRepository.findById(4L)).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> mfaService.completeEnrollment(4L, "123456"))
                .isInstanceOf(MfaService.MfaException.class)
                .hasMessageContaining("No pending");
    }

    // ---- verifyForLogin -----------------------------------------------------

    @Test
    void verifyForLogin_acceptsCurrentTotp() {
        User u = user(5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(u));
        MfaService.EnrollmentStart start = mfaService.startEnrollment(5L);
        mfaService.completeEnrollment(5L, totpFor(start.secret()));

        assertThat(mfaService.verifyForLogin(u, totpFor(start.secret()))).isTrue();
    }

    @Test
    void verifyForLogin_rejectsWrongCode() {
        User u = user(6L);
        u.setMfaSecret("JBSWY3DPEHPK3PXP");
        // Make sure backup-code lookup finds none, so we test pure TOTP rejection.
        when(backupCodeRepository.findUnusedByUserId(6L)).thenReturn(List.of());
        assertThat(mfaService.verifyForLogin(u, "000000")).isFalse();
    }

    @Test
    void verifyForLogin_acceptsAnUnusedBackupCode_andConsumesIt() {
        User u = user(7L);
        String plaintext = "X4Q7-K9F2-A3B1-M8H6";
        MfaBackupCode row = MfaBackupCode.builder()
                .id(99L).userId(7L)
                .codeHash(passwordEncoder.encode(plaintext))
                .createdAt(LocalDateTime.now())
                .build();
        when(backupCodeRepository.findUnusedByUserId(7L)).thenReturn(new ArrayList<>(List.of(row)));
        when(backupCodeRepository.markUsed(any(), any())).thenReturn(1); // consumed

        assertThat(mfaService.verifyForLogin(u, plaintext)).isTrue();
        verify(backupCodeRepository).markUsed(any(), any());
    }

    @Test
    void verifyForLogin_lostBackupCodeRace_returnsFalse() {
        User u = user(8L);
        String plaintext = "RACEDCODE0000ZZZZ"; // 16-char synthetic
        MfaBackupCode row = MfaBackupCode.builder()
                .id(1L).userId(8L)
                .codeHash(passwordEncoder.encode(plaintext))
                .build();
        when(backupCodeRepository.findUnusedByUserId(8L)).thenReturn(new ArrayList<>(List.of(row)));
        when(backupCodeRepository.markUsed(any(), any())).thenReturn(0); // someone else won

        assertThat(mfaService.verifyForLogin(u, plaintext)).isFalse();
    }

    @Test
    void verifyForLogin_blankInput_returnsFalse() {
        assertThat(mfaService.verifyForLogin(user(9L), "  ")).isFalse();
        assertThat(mfaService.verifyForLogin(user(9L), null)).isFalse();
    }

    // ---- disable + admin reset ---------------------------------------------

    @Test
    void disable_requiresWorkingCode() {
        User u = user(10L);
        u.setMfaEnabled(true);
        u.setMfaSecret("JBSWY3DPEHPK3PXP");
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> mfaService.disable(10L, "000000"))
                .isInstanceOf(MfaService.MfaException.class);
        assertThat(u.isMfaEnabled()).isTrue(); // unchanged
    }

    @Test
    void disable_validCode_wipesSecretAndBackupCodes() {
        User u = user(11L);
        u.setMfaEnabled(true);
        String secret = "JBSWY3DPEHPK3PXP";
        u.setMfaSecret(secret);
        when(userRepository.findById(11L)).thenReturn(Optional.of(u));

        mfaService.disable(11L, totpFor(secret));

        assertThat(u.isMfaEnabled()).isFalse();
        assertThat(u.getMfaSecret()).isNull();
        verify(backupCodeRepository).deleteAllForUser(11L);
    }

    @Test
    void disable_alreadyOff_isIdempotent() {
        User u = user(12L); // mfaEnabled defaults to false
        when(userRepository.findById(12L)).thenReturn(Optional.of(u));
        // No throw, no save expected; we don't even call verify in this branch.
        mfaService.disable(12L, "000000");
        assertThat(u.isMfaEnabled()).isFalse();
    }

    @Test
    void adminReset_wipesSecretAndCodes_andDisables() {
        User u = user(13L);
        u.setMfaEnabled(true);
        u.setMfaSecret("ABC");
        when(userRepository.findById(13L)).thenReturn(Optional.of(u));

        mfaService.adminReset(13L);

        assertThat(u.isMfaEnabled()).isFalse();
        assertThat(u.getMfaSecret()).isNull();
        verify(backupCodeRepository).deleteAllForUser(13L);
    }

    // ---- device-trust revocation on disable / reset -------------------------

    @Test
    void disable_validCode_alsoClearsDeviceTrust() {
        // Disabling MFA removes the second factor entirely, so any standing
        // "remember this device" bypass must be cleared too.
        DeviceTrustService trust = mock(DeviceTrustService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(mfaService, "deviceTrustService", trust);

        User u = user(20L);
        u.setMfaEnabled(true);
        String secret = "JBSWY3DPEHPK3PXP";
        u.setMfaSecret(secret);
        when(userRepository.findById(20L)).thenReturn(Optional.of(u));

        mfaService.disable(20L, totpFor(secret));

        verify(trust).clearTrustForUser(20L);
    }

    @Test
    void adminReset_alsoClearsDeviceTrust() {
        DeviceTrustService trust = mock(DeviceTrustService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(mfaService, "deviceTrustService", trust);

        User u = user(21L);
        u.setMfaEnabled(true);
        u.setMfaSecret("ABC");
        when(userRepository.findById(21L)).thenReturn(Optional.of(u));

        mfaService.adminReset(21L);

        verify(trust).clearTrustForUser(21L);
    }
}
