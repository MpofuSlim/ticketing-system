package com.innbucks.userservice.util;

/**
 * The one place that knows the platform-owner's bootstrap address.
 *
 * <p>{@code DataInitializer} seeds (or adopts) the SUPER_ADMIN row at
 * {@code BOOTSTRAP_ADMIN_EMAIL}. Every account-creation path that lets a
 * less-privileged caller choose an email therefore has to refuse that address:
 * a row sitting there when the seeder next boots is a row the seeder may try to
 * treat as the platform admin. {@link #PROPERTY} keeps the four sites from
 * drifting on the default, and {@link #matches} is the comparison all of them
 * use.
 *
 * <p><b>The comparison is case-insensitive on purpose.</b> {@code uk_users_email}
 * is case-sensitive (see the note on {@code UserRepository.existsByEmailIgnoreCase}),
 * so an exact-match guard would be bypassed by {@code Admin@innbucks.co.zw} —
 * a row that is a config re-spelling away from being adopted as the admin.
 */
public final class BootstrapAdminEmail {

    /** Address used when {@code BOOTSTRAP_ADMIN_EMAIL} is unset. */
    public static final String DEFAULT_ADDRESS = "admin@innbucks.co.zw";

    /**
     * Placeholder for {@code @Value}, so the seeder and the guards resolve the
     * same address from the same default. A compile-time constant, hence legal
     * in an annotation.
     */
    public static final String PROPERTY = "${BOOTSTRAP_ADMIN_EMAIL:" + DEFAULT_ADDRESS + "}";

    private BootstrapAdminEmail() {
    }

    /**
     * Whether {@code candidate} is the configured bootstrap admin address,
     * compared case-insensitively and ignoring surrounding whitespace.
     * Null-safe: a null on either side is not a match.
     */
    public static boolean matches(String configuredAddress, String candidate) {
        if (configuredAddress == null || candidate == null) {
            return false;
        }
        return configuredAddress.trim().equalsIgnoreCase(candidate.trim());
    }
}
