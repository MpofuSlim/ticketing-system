package com.innbucks.userservice.security;

import com.innbucks.userservice.config.MfaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts TOTP secrets at rest in {@code users.mfa_secret} with AES-GCM-256.
 *
 * <p>Stored value layout: {@code "v1:" + base64(iv || ciphertext || tag)} —
 * the {@code v1:} version prefix lets a future key-rotation / algorithm change
 * be added without needing to migrate existing rows (a decode that doesn't
 * start with a known prefix fails fast). 12-byte IV is fresh per encrypt;
 * GCM's 16-byte auth tag is appended by the JCE provider.
 *
 * <p>Bootstrapped from {@link MfaProperties#getEncryptionKey()} (Base64'd
 * 32 bytes). A wrong length or unparseable key makes the bean fail to start
 * so a misconfigured prod is impossible.
 *
 * <p>Static {@link #INSTANCE} is set by the constructor purely so the JPA
 * {@link com.innbucks.userservice.security.MfaSecretConverter} (which Hibernate
 * instantiates outside the Spring container) can reach the cipher. The pattern
 * is documented and contained to this one column; do not adopt it elsewhere.
 */
@Component
@Slf4j
public class MfaSecretCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN_BYTES = 12;       // 96-bit IV — GCM recommendation
    private static final int TAG_LEN_BITS = 128;      // GCM tag (default; declared explicitly)
    private static final int AES_KEY_LEN_BYTES = 32;  // AES-256
    private static final String VERSION_PREFIX = "v1:";

    private static volatile MfaSecretCipher INSTANCE;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MfaSecretCipher(MfaProperties properties) {
        this.key = parseKey(properties.getEncryptionKey());
        INSTANCE = this;
        log.info("MfaSecretCipher initialised (AES-GCM-256)");
    }

    /** Access point for the JPA AttributeConverter, which is not Spring-managed. */
    public static MfaSecretCipher instance() {
        MfaSecretCipher local = INSTANCE;
        if (local == null) {
            throw new IllegalStateException("MfaSecretCipher not yet initialised — "
                    + "the user-service context must be up before any User.mfaSecret is read/written");
        }
        return local;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt MFA secret", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Unknown MFA secret version prefix; cannot decrypt");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (combined.length < IV_LEN_BYTES + 16) {
                throw new IllegalStateException("MFA ciphertext too short to contain IV + tag");
            }
            byte[] iv = new byte[IV_LEN_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LEN_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LEN_BYTES);
            System.arraycopy(combined, IV_LEN_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MFA secret", e);
        }
    }

    private static SecretKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "MFA_ENCRYPTION_KEY is not configured — set it to a Base64-encoded 32-byte AES-GCM key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY is not valid Base64", e);
        }
        if (raw.length != AES_KEY_LEN_BYTES) {
            throw new IllegalStateException(
                    "MFA_ENCRYPTION_KEY must decode to exactly " + AES_KEY_LEN_BYTES
                            + " bytes (got " + raw.length + ")");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
