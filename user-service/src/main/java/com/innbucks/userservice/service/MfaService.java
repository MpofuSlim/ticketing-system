package com.innbucks.userservice.service;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.entity.MfaBackupCode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.UserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 2FA primitives: enrol a user, verify a TOTP-or-backup code, disable, admin
 * reset. Uses dev.samstevens.totp under the hood (RFC 6238, SHA-1, 6-digit,
 * 30-second step) — Google-Authenticator / Authy compatible.
 *
 * <p>Backup codes: at enrolment the service mints {@code backupCodeCount}
 * one-time codes, persists them bcrypt-hashed, and returns the PLAINTEXT list
 * exactly once. {@link #verifyForLogin} matches a 6-digit input against TOTP
 * first; anything that doesn't (e.g. an alphanumeric backup code) is then
 * matched bcrypt-style against the unused backup-code rows and atomically
 * consumed.
 *
 * <p>All state-changing operations are {@code @Transactional}.
 */
@Service
@Slf4j
public class MfaService {

    /** Allow one time-step before / after now — covers small client-server clock skew. */
    private static final int TIME_DISCREPANCY_STEPS = 1;

    /** Plain-text backup code shape: 4 groups of 4 base32-ish chars (`X4Q7-K9F2-A3B1-M8H6`). */
    private static final int BACKUP_CODE_GROUPS = 4;
    private static final int BACKUP_CODE_GROUP_LEN = 4;
    /** Avoids 0/O and 1/I/L confusion in print. */
    private static final char[] BACKUP_CODE_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private final UserRepository userRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaProperties properties;

    // Trusted-device collaborator. Field-injected (optional) rather than a
    // constructor param so the existing MfaServiceTest construction site doesn't
    // widen. Null in a plain unit test → disabling MFA simply doesn't clear
    // device trust there (no Spring context, no devices to clear anyway).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeviceTrustService deviceTrustService;

    // Field-injected (optional), same reasoning as deviceTrustService — keeps the
    // constructor and any plain unit test from widening. Null in a no-Spring test
    // → no security alert is fired.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private void publishSecurityAlert(User user, com.innbucks.userservice.event.AccountSecurityAlertEvent.Type type) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishEvent(new com.innbucks.userservice.event.AccountSecurityAlertEvent(
                user.getId(), user.getFirstName(), user.getEmail(), user.getPhoneNumber(),
                user.hasRole(User.Role.CUSTOMER), type));
    }

    // A09 audit coverage for the MFA lifecycle. Field-injected (required=false)
    // so the existing MfaServiceTest construction site doesn't widen; null there
    // => audit(...) is a no-op. AuditContext.none() because these run below the
    // controller (no HTTP request threaded down); actor == target == the user.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;

    private void audit(AuditEventType type, Long userId) {
        if (auditService != null) {
            auditService.recordSuccess(type,
                    String.valueOf(userId), AuditService.ACTOR_TYPE_USER,
                    String.valueOf(userId), AuditService.TARGET_TYPE_USER,
                    null, AuditContext.none());
        }
    }

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1);
    private final CodeVerifier codeVerifier;
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final SecureRandom random = new SecureRandom();

    public MfaService(UserRepository userRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      PasswordEncoder passwordEncoder,
                      MfaProperties properties) {
        this.userRepository = userRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, new SystemTimeProvider());
        verifier.setAllowedTimePeriodDiscrepancy(TIME_DISCREPANCY_STEPS);
        this.codeVerifier = verifier;
    }

    // ------------------------------------------------------------------
    // Enrolment
    // ------------------------------------------------------------------

    /** Mint a fresh secret + QR for a user who isn't enrolled yet (or has just reset). */
    @Transactional
    public EnrollmentStart startEnrollment(Long userId) {
        User user = loadUser(userId);
        String secret = secretGenerator.generate();
        // Persist immediately so /complete can verify the user's code against
        // the SAME secret we displayed. Encrypted at rest via the converter.
        user.setMfaSecret(secret);
        user.setMfaEnabled(false);              // still pending until /complete
        userRepository.save(user);

        String accountLabel = accountLabel(user);
        QrData qrData = new QrData.Builder()
                .label(accountLabel)
                .secret(secret)
                .issuer(properties.getIssuer())
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        String qrPngBase64;
        try {
            byte[] png = qrGenerator.generate(qrData);
            qrPngBase64 = Base64.getEncoder().encodeToString(png);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render TOTP QR code", e);
        }
        log.info("MFA enrolment started userId={}", userId);
        return new EnrollmentStart(secret, qrData.getUri(), qrPngBase64);
    }

    /**
     * Verify the user actually has the secret loaded (they entered a working
     * TOTP code), flip mfaEnabled=true, mint and persist a fresh set of backup
     * codes, and return their plaintext (once-only — never again).
     */
    @Transactional
    public List<String> completeEnrollment(Long userId, String submittedCode) {
        User user = loadUser(userId);
        if (user.getMfaSecret() == null) {
            throw new MfaException("No pending MFA enrolment for this user");
        }
        if (submittedCode == null || !codeVerifier.isValidCode(user.getMfaSecret(), submittedCode.trim())) {
            throw new MfaException("That code didn't match. Try the next one your app shows.");
        }
        user.setMfaEnabled(true);
        userRepository.save(user);

        // Wipe any prior batch (e.g. after admin reset → re-enrol) before
        // persisting the new one.
        backupCodeRepository.deleteAllForUser(userId);
        List<String> plaintext = new ArrayList<>(properties.getBackupCodeCount());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < properties.getBackupCodeCount(); i++) {
            String code = generateBackupCode();
            plaintext.add(code);
            backupCodeRepository.save(MfaBackupCode.builder()
                    .userId(userId)
                    .codeHash(passwordEncoder.encode(code))
                    .createdAt(now)
                    .build());
        }
        log.info("MFA enrolment completed userId={} backupCodes={}", userId, plaintext.size());
        publishSecurityAlert(user, com.innbucks.userservice.event.AccountSecurityAlertEvent.Type.MFA_ENABLED);
        audit(AuditEventType.MFA_ENROLLED, userId);
        return plaintext;
    }

    // ------------------------------------------------------------------
    // Verification at login
    // ------------------------------------------------------------------

    /**
     * Login-step-2 check. Returns true if {@code submitted} matches the user's
     * current TOTP code OR an unused backup code (which is then atomically
     * consumed). Does not touch the user row on success — the login flow does
     * its own tokenVersion bump etc.
     */
    @Transactional
    public boolean verifyForLogin(User user, String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return false;
        }
        String code = submitted.trim();
        // TOTP first — cheapest check and the common case.
        if (user.getMfaSecret() != null && codeVerifier.isValidCode(user.getMfaSecret(), code)) {
            return true;
        }
        // Fall through to backup codes. We have to scan + bcrypt-match because
        // each row has its own salt; the typical user has ≤10 unused codes.
        for (MfaBackupCode candidate : backupCodeRepository.findUnusedByUserId(user.getId())) {
            if (passwordEncoder.matches(code, candidate.getCodeHash())) {
                int consumed = backupCodeRepository.markUsed(
                        candidate.getId(), LocalDateTime.now(ZoneOffset.UTC));
                if (consumed == 1) {
                    log.info("Backup code consumed userId={}", user.getId());
                    audit(AuditEventType.MFA_BACKUP_CODE_USED, user.getId());
                    return true;
                }
                // Lost a race — the code was just consumed by a concurrent
                // verify. Reject this attempt; the user resubmits with another.
                log.info("Backup-code race lost userId={}", user.getId());
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Opt-in disable. Requires a fresh TOTP/backup code so a stolen session can't disable MFA. */
    @Transactional
    public void disable(Long userId, String submittedCode) {
        User user = loadUser(userId);
        if (!user.isMfaEnabled()) {
            return; // idempotent
        }
        if (!verifyForLogin(user, submittedCode)) {
            throw new MfaException("That code didn't match. MFA was not disabled.");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
        backupCodeRepository.deleteAllForUser(userId);
        clearDeviceTrust(userId);
        log.info("MFA disabled userId={}", userId);
        publishSecurityAlert(user, com.innbucks.userservice.event.AccountSecurityAlertEvent.Type.MFA_DISABLED);
        audit(AuditEventType.MFA_DISABLED, userId);
    }

    /** SUPER_ADMIN recovery: wipe a user's MFA so they can re-enrol on next login. */
    @Transactional
    public void adminReset(Long userId) {
        User user = loadUser(userId);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
        backupCodeRepository.deleteAllForUser(userId);
        clearDeviceTrust(userId);
        log.info("MFA reset by admin userId={}", userId);
        publishSecurityAlert(user, com.innbucks.userservice.event.AccountSecurityAlertEvent.Type.MFA_DISABLED);
        audit(AuditEventType.MFA_ADMIN_RESET, userId);
    }

    /**
     * Clears any "remember this device" trust on the user's devices. Disabling
     * or resetting MFA removes the second factor entirely, so a standing
     * trusted-device bypass would be meaningless (and a loose end) — wipe it.
     * No-op when the collaborator isn't wired (plain unit test).
     */
    private void clearDeviceTrust(Long userId) {
        if (deviceTrustService != null) {
            deviceTrustService.clearTrustForUser(userId);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    /** Label embedded in the otpauth:// URI. Prefer email so authenticator-app rows are recognisable. */
    private static String accountLabel(User user) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank()) {
            return user.getPhoneNumber();
        }
        return "user-" + user.getId();
    }

    private String generateBackupCode() {
        StringBuilder sb = new StringBuilder(BACKUP_CODE_GROUPS * (BACKUP_CODE_GROUP_LEN + 1) - 1);
        for (int g = 0; g < BACKUP_CODE_GROUPS; g++) {
            if (g > 0) sb.append('-');
            for (int c = 0; c < BACKUP_CODE_GROUP_LEN; c++) {
                sb.append(BACKUP_CODE_ALPHABET[random.nextInt(BACKUP_CODE_ALPHABET.length)]);
            }
        }
        return sb.toString();
    }

    /** Result of {@link #startEnrollment}. */
    public record EnrollmentStart(String secret, String otpauthUri, String qrPngBase64) {
    }

    /** Thrown for any user-visible MFA failure; mapped to 400 by the global handler. */
    public static class MfaException extends RuntimeException {
        public MfaException(String message) {
            super(message);
        }
    }
}
