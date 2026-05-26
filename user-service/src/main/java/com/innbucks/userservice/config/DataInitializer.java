package com.innbucks.userservice.config;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.Services;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashSet;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_EMAIL = "admin@innbucks.co.zw";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        User existing = userRepository.findByEmail(ADMIN_EMAIL).orElse(null);
        if (existing == null) {
            User admin = User.builder()
                    .firstName("Super")
                    .lastName("Admin")
                    .email(ADMIN_EMAIL)
                    .password(passwordEncoder.encode("#Pass123"))
                    .phoneNumber("0000000000")
                    .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                    .defaultServices(new LinkedHashSet<>(Services.ALL_BUNDLES))
                    .active(true)
                    .approved(true)
                    .build();
            userRepository.save(admin);
            System.out.println("Super admin user created successfully.");
            return;
        }

        // Idempotent migration for any pre-existing admin row whose roles or
        // defaultServices were never populated (e.g. created before the
        // join tables existed).
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
            System.out.println("Super admin user updated to current schema.");
        } else {
            System.out.println("Super admin user already exists.");
        }
    }
}
