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

import java.util.EnumSet;
import java.util.LinkedHashSet;

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

    @Value("${BOOTSTRAP_ADMIN_EMAIL:admin@innbucks.co.zw}")
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
                    .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                    .defaultServices(new LinkedHashSet<>(Services.ALL_BUNDLES))
                    .active(true)
                    .approved(true)
                    .mustChangePassword(true)
                    .build();
            userRepository.save(admin);
            log.info("Super admin '{}' seeded; must change password on first login.", adminEmail);
            return;
        }

        // Idempotent migration for any pre-existing admin row whose roles or
        // defaultServices were never populated (e.g. created before the
        // join tables existed). Does NOT touch the password.
        boolean changed = false;
        if (existing.getRoles() == null || !existing.getRoles().contains(User.Role.SUPER_ADMIN)) {
            EnumSet<User.Role> roles = EnumSet.of(User.Role.SUPER_ADMIN);
            if (existing.getRoles() != null) roles.addAll(existing.getRoles());
            existing.setRoles(roles);
            changed = true;
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
