package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;

import java.util.Comparator;
import java.util.Locale;

/**
 * The order of the console's System Users list ({@code GET /admin/users}).
 *
 * <p>Owner decision (2026-10-08): "platform owners should appear on the top of
 * the list ... then the rest should be names in alphabetical order". Until then
 * the list came back in whatever order Postgres produced, which changed with the
 * plan it picked, so a row an operator had just found could move between two
 * refreshes.
 *
 * <ol>
 *   <li>Accounts holding {@code SUPER_ADMIN} (the platform owners) first, then
 *       everyone else.</li>
 *   <li>Within each group, alphabetical by first name, then by last name,
 *       ignoring case ({@link Locale#ROOT}, so the order does not depend on the
 *       JVM's locale): "alice" sits beside "Alice" instead of after "Zoe".</li>
 *   <li>A missing or blank name sorts after every account that has one, so a
 *       half-filled row never heads the page.</li>
 *   <li>Ties by {@code id}, ascending, so the order is TOTAL: two accounts with
 *       the same name keep the same places on every load.</li>
 * </ol>
 *
 * <p>Applied in Java after loading, not in SQL: the list is unpaged and already
 * fully in memory, and "holds SUPER_ADMIN" is a property of an element
 * collection, which an {@code ORDER BY} could only reach through a join that
 * duplicates rows.
 */
public final class AdminUserOrder {

    /** Present, non-blank names in alphabetical order; absent ones after them. */
    private static final Comparator<String> NAMES = Comparator.nullsLast(Comparator.naturalOrder());

    /** The order {@code GET /admin/users} returns its rows in. */
    public static final Comparator<User> SYSTEM_USERS =
            Comparator.comparingInt((User u) -> isPlatformOwner(u) ? 0 : 1)
                    .thenComparing(u -> nameKey(u.getFirstName()), NAMES)
                    .thenComparing(u -> nameKey(u.getLastName()), NAMES)
                    .thenComparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private AdminUserOrder() {
    }

    private static boolean isPlatformOwner(User user) {
        return user.hasRole(User.Role.SUPER_ADMIN);
    }

    /**
     * What a name sorts by: lower-cased in {@link Locale#ROOT} with surrounding
     * whitespace dropped, or {@code null} — sorted last — when there is no name.
     */
    static String nameKey(String name) {
        if (name == null || name.isBlank()) return null;
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
