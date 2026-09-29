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
 * one-time codes, persists them hashed via the shared delegating
 * {@code PasswordEncoder} (Argon2id — the same encoder used for user passwords),
 * and returns the PLAINTEXT list exactly once. {@link #verifyForLogin} matches a
 * 6-digit input against TOTP first; anything that doesn't (e.g. an alphanumeric
 * backup code) is then matched against the unused backup-code rows via the
 * encoder and atomically consumed.
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
    /**
     * Ends the target's sessions on an admin reset. Required: an admin reset
     * that leaves the old sessions — and any half-finished MFA challenge —
     * standing is exactly the gap it exists to close.
     */
    private final TokenVersionBumper tokenVersionBumper;
    /**
     * An admin reset needs the caller to hold everything the target holds, and
     * every NAMED staff role it holds — a narrower administrator must not be able
     * to strip a broader one's second factor. Required: a missing guard must fail the wiring, not the check.
     */
    private final RoleGrantGuard roleGrantGuard;

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
                      MfaProperties properties,
                      TokenVersionBumper tokenVersionBumper,
                      RoleGrantGuard roleGrantGuard) {
        this.userRepository = userRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.tokenVersionBumper = tokenVersionBumper;
        this.roleGrantGuard = roleGrantGuard;
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

    /**
     * Administrator recovery ({@code POST /admin/users/{id}/mfa/reset}): wipe a
     * user's second factor so they re-enrol on their next sign-in.
     *
     * <ul>
     *   <li><b>Refuses a SUPER_ADMIN target</b> — 403 {@code target_not_manageable}
     *       ({@code reason: super_admin}). Stripping the platform owner's second
     *       factor is a takeover step, not a recovery; that account is managed
     *       through {@code BOOTSTRAP_ADMIN_PASSWORD} only, the same rule
     *       {@code setActive} and {@code setRoles} already apply.</li>
     *   <li><b>Refuses a target holding a permission, or a NAMED staff role, the
     *       caller does not</b> — 403 {@code target_not_manageable} ({@code reason:
     *       exceeds_your_authority}), read from the caller's live roles
     *       ({@link RoleGrantGuard#requireMayManage}). Resetting
     *       someone's 2FA opens their account to whoever holds their password, so
     *       a narrower administrator must not be able to do it to a broader one.</li>
     *   <li><b>Takes effect immediately.</b> Bumps {@code tokenVersion}
     *       (published after commit), so every access token AND every pending
     *       mfaToken the account holds dies now: "must re-enrol on next login"
     *       used to be dodgeable by any session that was already open.</li>
     *   <li><b>Audited as the ADMIN acting on the USER.</b> The row used to name
     *       the target as its own actor, so the log could not say who reset
     *       whose 2FA. {@code note} is the operator's optional reason.</li>
     * </ul>
     *
     * @param adminEmail the acting administrator ({@code authentication.getName()})
     * @param note       optional free text; cleaned by {@link #cleanNote} here, whatever the
     *                   caller validated
     */
    @Transactional
    public void adminReset(Long userId, String adminEmail, String note, AuditContext auditContext) {
        User user = loadUser(userId);
        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            log.warn("MFA admin reset refused on SUPER_ADMIN target userId={} by={}",
                    userId, adminEmail == null ? "system" : adminEmail);
            throw com.innbucks.userservice.exception.StaffPolicyException.targetNotManageable(
                    com.innbucks.userservice.exception.StaffPolicyException.REASON_SUPER_ADMIN);
        }
        roleGrantGuard.requireMayManage(roleGrantGuard.resolveCaller(adminEmail), user);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        userRepository.save(user);
        backupCodeRepository.deleteAllForUser(userId);
        clearDeviceTrust(userId);
        long newVersion = tokenVersionBumper.bump(user);
        log.info("MFA reset by admin userId={} by={} newTokenVersion={}",
                userId, adminEmail == null ? "system" : adminEmail, newVersion);
        publishSecurityAlert(user, com.innbucks.userservice.event.AccountSecurityAlertEvent.Type.MFA_DISABLED);
        if (auditService != null) {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("targetEmail", user.getEmail() == null ? "" : user.getEmail());
            metadata.put("tokenVersion", newVersion);
            String cleanNote = cleanNote(note);
            if (cleanNote != null) {
                metadata.put("note", cleanNote);
            }
            auditService.recordSuccess(AuditEventType.MFA_ADMIN_RESET,
                    adminEmail == null ? "system" : adminEmail,
                    adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                    String.valueOf(userId), AuditService.TARGET_TYPE_USER,
                    metadata,
                    auditContext == null ? AuditContext.none() : auditContext);
        }
    }

    /** Longest admin-reset note kept — the same bound {@code AdminMfaResetRequestDTO} validates at the edge. */
    static final int MAX_NOTE_LENGTH = 500;

    /**
     * The admin-reset note as it may be written into the audit metadata: markup
     * stripped ({@link com.innbucks.userservice.util.HtmlSanitizer#stripAll} — the
     * audit log is rendered in the console), control and invisible formatting
     * characters (line breaks, NUL, bidi overrides) replaced by spaces so a note
     * cannot forge extra lines in a log view or reorder what a reviewer reads,
     * trimmed, and capped at {@link #MAX_NOTE_LENGTH}. The HTTP edge already
     * refuses an over-long note with a 400; this is the service's own bound, for
     * every other caller. Blank after cleaning means no note.
     */
    static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        // Before AND after the markup strip: before, because the HTML parser
        // rewrites some control characters rather than dropping them; after,
        // because decoding an entity (&#x202E;) can produce one.
        String cleaned = invisiblesToSpace(com.innbucks.userservice.util.HtmlSanitizer.stripAll(
                invisiblesToSpace(note)))
                .replaceAll(" {2,}", " ")
                .strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_NOTE_LENGTH) {
            int end = MAX_NOTE_LENGTH;
            if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                end--; // never cut a character in half
            }
            cleaned = cleaned.substring(0, end).strip();
        }
        return cleaned;
    }

    private static String invisiblesToSpace(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}]+", " ");
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
