package com.innbucks.userservice.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts {@code User.mfaSecret} on write and decrypts on read,
 * so callers always see the plaintext base32 secret while the DB row stores
 * versioned AES-GCM ciphertext.
 *
 * <p>Hibernate constructs converters outside the Spring container, so we reach
 * the singleton cipher via {@link MfaSecretCipher#instance()} rather than DI.
 * The cipher initialises during Spring startup; the converter is only used
 * once JPA is also up, so the ordering is safe.
 */
@Converter(autoApply = false)
public class MfaSecretConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        return MfaSecretCipher.instance().encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return MfaSecretCipher.instance().decrypt(stored);
    }
}
