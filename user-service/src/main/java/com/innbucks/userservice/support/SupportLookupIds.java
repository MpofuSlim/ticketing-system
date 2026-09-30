package com.innbucks.userservice.support;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * {@code SLK-7Q2M9X}: a lookup id. Crockford base32 without I, L, O and U (it is
 * read aloud between colleagues), six characters — about 30 bits from a
 * {@link SecureRandom}.
 *
 * <p>That is enough because the id is not the control: a lookup id only works
 * for the agent who issued it, for {@code SUPPORT_LOOKUP_BINDING_TTL} (30
 * minutes), and only for targets inside its own resolved keys. A guessed id is
 * someone else's (refused as {@code lookup_expired}) or stale. Uniqueness is
 * enforced by {@code uk_support_access_log_lookup}; a collision is re-drawn.
 */
public final class SupportLookupIds {

    public static final Pattern FORMAT = Pattern.compile("^SLK-[0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$");

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SupportLookupIds() {
    }

    public static String next() {
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return "SLK-" + new String(out);
    }

    /** True for a well-formed id — upper case, as issued. */
    public static boolean wellFormed(String id) {
        return id != null && FORMAT.matcher(id).matches();
    }
}
