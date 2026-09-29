package com.innbucks.userservice.devicesecurity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** RSA keys for tests only, generated per JVM. */
public final class TestKeys {

    private static final KeyPair PAIR = generate();

    private TestKeys() {
    }

    public static KeyPair pair() {
        return PAIR;
    }

    /** PKCS#8 PEM, the shape DEVICE_SECURITY_TICKET_PRIVATE_KEY takes. */
    public static String privatePem() {
        return pem(PAIR);
    }

    public static String pem(KeyPair pair) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
