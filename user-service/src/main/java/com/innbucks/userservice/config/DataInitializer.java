package com.innbucks.userservice.config;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        String adminEmail = "admin@innbucks.co.zw";
        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = User.builder()
                    .firstName("Super")
                    .lastName("Admin")
                    .email(adminEmail)
                    .password(passwordEncoder.encode("#Pass123"))
                    .phoneNumber("0000000000")
                    .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                    .defaultServices(Set.of("ticketing", "loyalty"))
                    .build();
            userRepository.save(admin);
            System.out.println("Super admin user created successfully.");
        } else {
            System.out.println("Super admin user already exists.");
        }
    }
}
