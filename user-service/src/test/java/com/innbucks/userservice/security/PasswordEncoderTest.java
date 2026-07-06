package com.innbucks.userservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the A02 password-hashing migration: new passwords must hash with
 * Argon2id, and — critically — the legacy UNPREFIXED BCrypt hashes already in the
 * DB (written by the previous {@code new BCryptPasswordEncoder()}) must still
 * verify, so the switch never locks an existing user out of their account.
 */
class PasswordEncoderTest {

    // Build the bean exactly as SecurityConfig does (jwtFilter is unused here).
    private final PasswordEncoder encoder = new SecurityConfig(null).passwordEncoder();

    @Test
    void newPasswordsAreHashedWithArgon2id() {
        String hash = encoder.encode("s3cret-Pass!");
        assertThat(hash).startsWith("{argon2}");
        assertThat(encoder.matches("s3cret-Pass!", hash)).isTrue();
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    void legacyRawBCryptHashesStillVerify() {
        // A hash from the previous encoder: raw, UNPREFIXED ($2a$...). The
        // DelegatingPasswordEncoder's default-for-matches must still verify it.
        String legacy = new BCryptPasswordEncoder().encode("old-Password-9");
        assertThat(legacy).doesNotStartWith("{");
        assertThat(encoder.matches("old-Password-9", legacy)).isTrue();
        assertThat(encoder.matches("not-it", legacy)).isFalse();
    }

    @Test
    void prefixedBCryptHashesAlsoVerify() {
        String prefixed = "{bcrypt}" + new BCryptPasswordEncoder().encode("mixed-1");
        assertThat(encoder.matches("mixed-1", prefixed)).isTrue();
    }
}
