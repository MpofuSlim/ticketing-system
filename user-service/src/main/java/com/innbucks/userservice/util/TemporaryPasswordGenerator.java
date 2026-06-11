package com.innbucks.userservice.util;

import java.security.SecureRandom;

/**
 * Generates a one-time temporary password for a freshly-onboarded system user
 * (a SUPER_ADMIN-approved organizer / merchant-admin, or a merchant-created
 * shop staff member).
 *
 * <p>Replaces the old shared {@code #Pass123} default. That constant was
 * publicly known (it lived in the source, the Swagger docs, and onboarding
 * emails), so anyone who knew a system user's email could log in as them in
 * the window between approval and the forced first-login password change. A
 * per-user random value closes that hole: the password is delivered to the
 * user over their own notification channel (email → SMS → WhatsApp) and is
 * still force-changed on first login.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>{@link SecureRandom}</b>, not {@code java.util.Random} — these are
 *       credentials, so the generator must be cryptographically strong.</li>
 *   <li><b>Unambiguous alphabet</b> — excludes {@code 0/O/o} and {@code 1/l/I}
 *       — because the user keys the value off an SMS and we don't want
 *       "is that an oh or a zero" support tickets.</li>
 *   <li><b>Hyphen-grouped</b> ({@code XXXX-XXXX-XXXX}) for readability when
 *       typing it in by hand.</li>
 *   <li>12 characters over a 56-symbol alphabet ≈ 70 bits of entropy — far
 *       beyond brute-forcing within the brief change-on-first-login window.</li>
 * </ul>
 *
 * <p>The bootstrap super admin is NOT created through either onboarding flow
 * (it is seeded by {@code DataInitializer} from {@code BOOTSTRAP_ADMIN_PASSWORD}),
 * so it never receives a generated password.
 */
public final class TemporaryPasswordGenerator {

    // 24 upper (no I,O) + 24 lower (no l,o) + 8 digits (no 0,1) = 56 symbols.
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GROUPS = 3;
    private static final int GROUP_LEN = 4;

    private TemporaryPasswordGenerator() {
    }

    /** @return e.g. {@code "Kp7r-Qn4m-Tx9j"} — a fresh value on every call. */
    public static String generate() {
        StringBuilder sb = new StringBuilder(GROUPS * GROUP_LEN + (GROUPS - 1));
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < GROUP_LEN; i++) {
                sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
            }
        }
        return sb.toString();
    }
}
