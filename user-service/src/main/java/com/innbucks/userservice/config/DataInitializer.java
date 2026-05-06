package com.innbucks.userservice.config;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        String adminEmail = "admin@innbucks.co.zw";
        User existing = userRepository.findByEmail(adminEmail).orElse(null);
        if (existing == null) {
            User admin = User.builder()
                    .firstName("Super")
                    .lastName("Admin")
                    .email(adminEmail)
                    .password(passwordEncoder.encode("#Pass123"))
                    .phoneNumber("0000000000")
                    .role(User.Role.SUPER_ADMIN)
                    .active(true)
                    .build();
            userRepository.save(admin);
            System.out.println("Super admin user created successfully.");
            return;
        }
        // Idempotent back-fill: ensure the seeded admin is always active so it
        // can immediately log in even if the row pre-dated the `active` column.
        if (!existing.isActive()) {
            existing.setActive(true);
            userRepository.save(existing);
            System.out.println("Super admin user activated.");
        } else {
            System.out.println("Super admin user already exists.");
        }
    }
}
