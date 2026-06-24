package com.innbucks.userservice.security;

import com.innbucks.userservice.config.MfaProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static MfaSecretCipher newCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        MfaProperties props = new MfaProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        return new MfaSecretCipher(props);
    }

    @Test
    void roundTripsPlaintext() {
        MfaSecretCipher cipher = newCipher();
        String plaintext = "JBSWY3DPEHPK3PXP"; // typical base32 TOTP secret
        String stored = cipher.encrypt(plaintext);
        assertThat(stored).startsWith("v1:").doesNotContain(plaintext);
        assertThat(cipher.decrypt(stored)).isEqualTo(plaintext);
    }

    @Test
    void nullIsPreserved() {
        MfaSecretCipher cipher = newCipher();
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void freshIvPerEncrypt_sameInputDifferentCiphertext() {
        // GCM with a fresh IV must produce different ciphertext on every encrypt,
        // otherwise the IV reuse breaks confidentiality. Smoke check the contract.
        MfaSecretCipher cipher = newCipher();
        String stored1 = cipher.encrypt("ABCDEFGHIJ");
        String stored2 = cipher.encrypt("ABCDEFGHIJ");
        assertThat(stored1).isNotEqualTo(stored2);
        assertThat(cipher.decrypt(stored1)).isEqualTo(cipher.decrypt(stored2)).isEqualTo("ABCDEFGHIJ");
    }

    @Test
    void tamperedCiphertext_failsAuthentication() {
        MfaSecretCipher cipher = newCipher();
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP");
        // Flip the last byte of the Base64 payload — GCM auth tag should reject.
        String tampered = stored.substring(0, stored.length() - 2)
                + (stored.endsWith("=") ? "Aa" : "ZX");
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownVersionPrefix() {
        MfaSecretCipher cipher = newCipher();
        assertThatThrownBy(() -> cipher.decrypt("v9:something"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version prefix");
    }

    @Test
    void refusesShortKeyAtConstruction() {
        MfaProperties bad = new MfaProperties();
        bad.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16])); // AES-128 length
        assertThatThrownBy(() -> new MfaSecretCipher(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void refusesMissingKey() {
        MfaProperties bad = new MfaProperties();
        bad.setEncryptionKey("");
        assertThatThrownBy(() -> new MfaSecretCipher(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
