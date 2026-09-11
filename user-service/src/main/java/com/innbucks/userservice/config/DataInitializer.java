package com.innbucks.userservice.config;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.Services;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    /**
     * Dev/test-only fallback so a fresh clone and CI seed a usable admin without
     * config. NEVER used under the `prod` profile — see {@link #resolveSeedPassword()}.
     * Previously this literal was the unconditional admin password, which shipped
     * a publicly-known SUPER_ADMIN backdoor into production.
     */
    private static final String DEV_FALLBACK_PASSWORD = "#Pass123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    // A09: audit the first-run bootstrap SUPER_ADMIN creation. Field-injected
    // (required=false) so tests constructing this runner directly don't widen.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.innbucks.userservice.service.AuditService auditService;

    @Value(com.innbucks.userservice.util.BootstrapAdminEmail.PROPERTY)
    private String adminEmail;

    @Value("${BOOTSTRAP_ADMIN_PASSWORD:}")
    private String adminPassword;

    /** Deployment country pin (ISO 3166-1 alpha-2). The seeded admin is
     *  anchored to this cell; same key the rest of the service uses. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

    @Override
    @Transactional
    public void run(String... args) {
        User existing = userRepository.findByEmail(adminEmail).orElse(null);
        if (existing == null) {
            String password = resolveSeedPassword();
            if (password == null) {
                // prod with no BOOTSTRAP_ADMIN_PASSWORD: refuse to seed a
                // known-credential admin (that was the backdoor). The operator
                // sets BOOTSTRAP_ADMIN_PASSWORD to bootstrap the first admin.
                log.error("No BOOTSTRAP_ADMIN_PASSWORD set under the prod profile — skipping super-admin "
                        + "seed. Set BOOTSTRAP_ADMIN_PASSWORD (strong, unique) to bootstrap an admin.");
                return;
            }
            User admin = User.builder()
                    .firstName("Super")
                    .lastName("Admin")
                    .email(adminEmail)
                    .password(passwordEncoder.encode(password))
                    .phoneNumber("0000000000")
                    // home_country = the deployment country. The seed phone is
                    // a placeholder, not an MSISDN, so we can't derive — use
                    // the cell pin instead.
                    .homeCountry(deploymentCountry)
                    .roles(new LinkedHashSet<>(Set.of(User.Role.SUPER_ADMIN.name())))
                    .defaultServices(new LinkedHashSet<>(Services.ALL_BUNDLES))
                    .active(true)
                    .approved(true)
                    .mustChangePassword(true)
                    .build();
            userRepository.save(admin);
            log.info("Super admin '{}' seeded; must change password on first login.", adminEmail);
            if (auditService != null) {
                auditService.recordSuccess(
                        com.innbucks.userservice.service.AuditEventType.BOOTSTRAP_ADMIN_CREATED,
                        null, com.innbucks.userservice.service.AuditService.ACTOR_TYPE_SYSTEM,
                        adminEmail, com.innbucks.userservice.service.AuditService.TARGET_TYPE_USER,
                        java.util.Map.of("email", adminEmail),
                        com.innbucks.userservice.service.AuditContext.none());
            }
            return;
        }

        // A row already sitting at this address is only ours to adopt if it
        // looks like an admin row THIS seeder wrote. Anything else got there by
        // someone else claiming the address — a team member an organizer
        // onboarded, shop staff a merchant admin created, a customer who set it
        // on tier-2 registration — and adopting it would confer SUPER_ADMIN on
        // an account whose password that someone already knows, active and
        // approved, with no forced rotation. That is a silent privileged-account
        // mint, so refuse and leave the row exactly as it is.
        //
        // Reachable via an email rotation or a deleted admin row rather than by
        // a less-privileged caller acting alone: on a steady-state cell the
        // admin row already occupies the address, so the creation paths refuse
        // the duplicate. The guards in those paths (BootstrapAdminEmail) close
        // the pre-planting half; this closes the adoption half.
        //
        // Refusing is fail-closed for the privilege — the account keeps exactly
        // the authority it had — while still booting. Aborting startup instead
        // would turn a row a less-privileged caller can partly control into a
        // lever for taking user-service down cell-wide.
        String refusal = adoptionRefusalReason(existing);
        if (refusal != null) {
            log.error("REFUSING to adopt the pre-existing account at BOOTSTRAP_ADMIN_EMAIL '{}' as the platform "
                    + "SUPER_ADMIN: {}. This account was created by someone else and has been left untouched — "
                    + "no role, activation or approval change was made. Point BOOTSTRAP_ADMIN_EMAIL at an address "
                    + "no one else holds, or remove/rename the conflicting account, then restart.",
                    adminEmail, refusal);
            if (auditService != null) {
                auditService.recordFailure(
                        com.innbucks.userservice.service.AuditEventType.BOOTSTRAP_ADMIN_SEED_REFUSED,
                        null, com.innbucks.userservice.service.AuditService.ACTOR_TYPE_SYSTEM,
                        adminEmail, com.innbucks.userservice.service.AuditService.TARGET_TYPE_USER,
                        refusal, java.util.Map.of("email", adminEmail),
                        com.innbucks.userservice.service.AuditContext.none());
            }
            return;
        }

        // Idempotent migration for a pre-existing admin row whose roles or
        // defaultServices were never populated (e.g. created before the join
        // tables existed). Never rewrites the password itself — it may only
        // flag that the existing one has to be rotated, and only on the branch
        // that grants SUPER_ADMIN.
        boolean changed = false;
        boolean granted = false;
        if (existing.getRoles() == null || !existing.getRoles().contains(User.Role.SUPER_ADMIN.name())) {
            // Replace rather than merge. The guard above already established
            // the row holds no other role, so there is nothing to preserve —
            // and a merge is what would carry a foreign role through if that
            // guard is ever loosened.
            existing.setRoles(new LinkedHashSet<>(Set.of(User.Role.SUPER_ADMIN.name())));
            // We are conferring SUPER_ADMIN on a row whose password this seeder
            // did not choose, so that password is not a credential we can vouch
            // for. Force a rotation at next login so an inherited one cannot be
            // used as-is against the privilege just granted. Scoped to this
            // branch: a boot against a row that already holds SUPER_ADMIN never
            // re-arms the flag, so the admin is not asked again every restart.
            existing.setMustChangePassword(true);
            changed = true;
            granted = true;
        }
        LinkedHashSet<String> allBundles = new LinkedHashSet<>(Services.ALL_BUNDLES);
        if (existing.getDefaultServices() == null
                || !existing.getDefaultServices().containsAll(allBundles)) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(allBundles);
            if (existing.getDefaultServices() != null) merged.addAll(existing.getDefaultServices());
            existing.setDefaultServices(merged);
            changed = true;
        }
        if (!existing.isActive()) {
            existing.setActive(true);
            changed = true;
        }
        if (!existing.isApproved()) {
            existing.setApproved(true);
            changed = true;
        }
        if (changed) {
            userRepository.save(existing);
            log.info("Super admin '{}' updated to current schema.", adminEmail);
        }
        if (granted) {
            log.warn("Granted SUPER_ADMIN to the pre-existing account at '{}' and flagged it to change password "
                    + "at next login.", adminEmail);
            if (auditService != null) {
                auditService.recordSuccess(
                        com.innbucks.userservice.service.AuditEventType.BOOTSTRAP_ADMIN_ADOPTED,
                        null, com.innbucks.userservice.service.AuditService.ACTOR_TYPE_SYSTEM,
                        adminEmail, com.innbucks.userservice.service.AuditService.TARGET_TYPE_USER,
                        java.util.Map.of("email", adminEmail),
                        com.innbucks.userservice.service.AuditContext.none());
            }
        }
    }

    /**
     * Why the pre-existing row at {@code BOOTSTRAP_ADMIN_EMAIL} must NOT be
     * adopted as the platform SUPER_ADMIN, or {@code null} when it is safe to
     * adopt. The returned string is operator-facing — it lands in the ERROR log
     * and the audit row, so it names what is actually wrong with the row.
     *
     * <p>The role check is the load-bearing one: it is the only signal that
     * catches a CUSTOMER row, which carries none of the staff stamps. The stamp
     * checks cover a row whose roles were somehow cleared but which still
     * belongs to an organizer or a merchant.
     *
     * <p>A row with no roles at all and no stamps is the legacy admin this
     * migration branch exists for — created before the join tables, so it is
     * adoptable.
     */
    private String adoptionRefusalReason(User existing) {
        Set<String> roles = existing.getRoles();
        if (roles != null) {
            LinkedHashSet<String> others = new LinkedHashSet<>(roles);
            others.remove(User.Role.SUPER_ADMIN.name());
            if (!others.isEmpty()) {
                return "the account holds role(s) " + others + ", so it is not the platform admin account";
            }
        }
        if (existing.getCreatedByOrganizerUuid() != null) {
            return "the account is stamped as created by organizer " + existing.getCreatedByOrganizerUuid();
        }
        if (existing.getLoyaltyMerchantId() != null || existing.getLoyaltyShopId() != null) {
            return "the account carries loyalty scoping (merchantId=" + existing.getLoyaltyMerchantId()
                    + ", shopId=" + existing.getLoyaltyShopId() + "), so it is merchant staff";
        }
        return null;
    }

    /**
     * Resolve the seed password: the configured {@code BOOTSTRAP_ADMIN_PASSWORD}
     * if present; otherwise a dev-only fallback for non-prod convenience, or
     * {@code null} under the {@code prod} profile so we never seed a
     * known-credential admin in production.
     */
    private String resolveSeedPassword() {
        if (adminPassword != null && !adminPassword.isBlank()) {
            return adminPassword;
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            return null;
        }
        log.warn("BOOTSTRAP_ADMIN_PASSWORD not set; using dev-only fallback admin password (non-prod profile).");
        return DEV_FALLBACK_PASSWORD;
    }
}
