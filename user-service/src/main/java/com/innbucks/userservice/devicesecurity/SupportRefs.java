package com.innbucks.userservice.devicesecurity;

import java.security.SecureRandom;

/**
 * The reference a customer reads out to the call center ("SEC-8F2KQ7"). Crockford
 * base32 without I, L, O and U, so it survives being read over a phone line; six
 * characters (~30 bits) so two live blocks never share one in practice. The
 * admin portal looks a block up by it.
 */
public final class SupportRefs {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SupportRefs() {
    }

    public static String next() {
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return "SEC-" + new String(out);
    }

    /** Normalises what an agent typed: case, spaces, and the 0/O, 1/I/L confusions Crockford forgives. */
    public static String normalise(String typed) {
        if (typed == null) return null;
        String s = typed.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        if (!s.startsWith("SEC-")) {
            s = s.startsWith("SEC") ? "SEC-" + s.substring(3) : "SEC-" + s;
        }
        String body = s.substring(4).replace('O', '0').replace('I', '1').replace('L', '1');
        return "SEC-" + body;
    }
}
