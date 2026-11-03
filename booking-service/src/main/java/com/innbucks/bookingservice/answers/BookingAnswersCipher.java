package com.innbucks.bookingservice.answers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts a ticket's answers to the organizer's booking questions at rest
 * ({@code booking_items.answers}, V32) with AES-GCM-256. Organizers ask for
 * medical conditions, ID numbers and next of kin; a database read, a backup or
 * a dump must not hand them over.
 *
 * <p>Stored value: {@code "v1:" + base64(iv || ciphertext || tag)}, the layout
 * of user-service's {@code MfaSecretCipher}. The prefix leaves room for a
 * second key or algorithm without migrating rows.
 *
 * <p>The key is {@code app.booking.answers-key} ({@code BOOKING_ANSWERS_KEY}),
 * Base64 of exactly 32 bytes. Under a deployment profile
 * {@code ProductionSecretsGuard} refuses to boot without one. Outside one (dev,
 * test, it) a blank key is replaced by a random key for this process, with a
 * WARN: answers written then cannot be read after a restart, which only a
 * developer's database ever sees. A key that is set but malformed fails the
 * boot everywhere. Never rotate the key without re-encrypting the rows: answers
 * sealed with the old key would no longer open.
 */
@Component
@Slf4j
public class BookingAnswersCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN_BYTES = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final int KEY_LEN_BYTES = 32;
    static final String VERSION_PREFIX = "v1:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public BookingAnswersCipher(@Value("${app.booking.answers-key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt booking answers", e);
        }
    }

    /** The plaintext; throws when the value is not ours or the key does not open it. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Booking answers are not in a known sealed format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (combined.length < IV_LEN_BYTES + TAG_LEN_BITS / 8) {
                throw new IllegalStateException("Sealed booking answers are too short");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, combined, 0, IV_LEN_BYTES));
            byte[] plain = cipher.doFinal(combined, IV_LEN_BYTES, combined.length - IV_LEN_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt booking answers", e);
        }
    }

    private static SecretKey parseKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            byte[] raw = new byte[KEY_LEN_BYTES];
            new SecureRandom().nextBytes(raw);
            log.warn("BOOKING_ANSWERS_KEY is not set: booking answers are sealed with a random key for this "
                    + "process only and cannot be read after a restart. Fine for dev and tests; a deployment "
                    + "refuses to boot without the key (ProductionSecretsGuard).");
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BOOKING_ANSWERS_KEY is not valid Base64", e);
        }
        if (raw.length != KEY_LEN_BYTES) {
            throw new IllegalStateException("BOOKING_ANSWERS_KEY must decode to exactly " + KEY_LEN_BYTES
                    + " bytes (got " + raw.length + "). Generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
