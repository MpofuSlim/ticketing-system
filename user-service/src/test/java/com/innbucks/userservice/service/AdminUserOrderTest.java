package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The System Users order ({@link AdminUserOrder#SYSTEM_USERS}, owner decision
 * 2026-10-08): platform owners first, then everyone else; within each group by
 * first name, then last name, ignoring case; no name last; ties by id.
 */
class AdminUserOrderTest {

    private static User user(long id, String first, String last, User.Role... roles) {
        return User.builder().id(id).firstName(first).lastName(last).roles(User.roleNames(roles)).build();
    }

    private static List<Long> sortedIds(List<User> users) {
        List<User> shuffled = new ArrayList<>(users);
        // Reversed, so an order that merely survived from the input proves nothing.
        Collections.reverse(shuffled);
        return shuffled.stream().sorted(AdminUserOrder.SYSTEM_USERS).map(User::getId).toList();
    }

    @Test
    @DisplayName("owners and non-owners, mixed letter case and a missing name: owners first, then by name")
    void theWholeRule() {
        List<User> users = List.of(
                user(1, "bob", "Banda", User.Role.MERCHANT_ADMIN),
                user(2, "Zoe", "Zulu", User.Role.SUPER_ADMIN),
                user(3, "alice", "Moyo", User.Role.EVENT_ORGANIZER),
                user(4, null, "Nameless", User.Role.SHOP_ADMIN),
                user(5, "Alice", "Dube", User.Role.EVENT_ORGANIZER, User.Role.CUSTOMER),
                user(6, "Anesu", "Owner", User.Role.SUPER_ADMIN),
                user(7, "Bob", "Banda", User.Role.TEAM_MEMBER));

        assertThat(sortedIds(users))
                // Owners first, alphabetically among themselves...
                .containsExactly(6L, 2L,
                        // ...then everyone else: "Alice Dube" before "alice Moyo" (same first
                        // name ignoring case, so the last name decides), the two "Bob Banda"s by
                        // id, and the account with no first name last.
                        5L, 3L, 1L, 7L, 4L);
    }

    @Test
    @DisplayName("an owner precedes every non-owner, whatever the names")
    void ownersFirst() {
        assertThat(sortedIds(List.of(
                user(10, "Aaron", "Abel", User.Role.EVENT_ORGANIZER),
                user(11, "Zvikomborero", "Zhou", User.Role.SUPER_ADMIN))))
                .containsExactly(11L, 10L);
    }

    @Test
    @DisplayName("same first name: the last name decides, ignoring case")
    void lastNameBreaksAFirstNameTie() {
        assertThat(sortedIds(List.of(
                user(20, "Tendai", "moyo", User.Role.EVENT_ORGANIZER),
                user(21, "TENDAI", "Dube", User.Role.EVENT_ORGANIZER),
                user(22, "tendai", "Chikwanha", User.Role.EVENT_ORGANIZER))))
                .containsExactly(22L, 21L, 20L);
    }

    @Test
    @DisplayName("a blank or missing first name, then a blank or missing last name, sort after the named")
    void missingNamesSortLast() {
        assertThat(sortedIds(List.of(
                user(30, "  ", "Alpha", User.Role.EVENT_ORGANIZER),
                user(31, "Rudo", null, User.Role.EVENT_ORGANIZER),
                user(32, "Rudo", "Chari", User.Role.EVENT_ORGANIZER),
                user(33, null, null, User.Role.EVENT_ORGANIZER),
                user(34, "Zanele", "", User.Role.EVENT_ORGANIZER))))
                // Named first names first (Rudo Chari, Rudo with no surname, Zanele), then
                // the two with no first name: "Alpha" before the one with no name at all.
                .containsExactly(32L, 31L, 34L, 30L, 33L);
    }

    @Test
    @DisplayName("identical names are ordered by id, so the order is the same on every load")
    void tiesByIdAscending() {
        assertThat(sortedIds(List.of(
                user(42, "Farai", "Dube", User.Role.SHOP_ADMIN),
                user(40, "farai", "dube", User.Role.SHOP_ADMIN),
                user(41, "Farai ", " Dube", User.Role.SHOP_ADMIN))))
                .containsExactly(40L, 41L, 42L);
    }

    @Test
    @DisplayName("the comparison is Locale.ROOT, not the JVM default (Turkish lower-cases I to a dotless i)")
    void independentOfTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // Under tr-TR "IVAN".toLowerCase() is "ıvan" (dotless), which sorts after
            // every Latin letter; under ROOT it is "ivan", which sorts after "irene".
            assertThat(sortedIds(List.of(
                    user(50, "IVAN", "Ncube", User.Role.EVENT_ORGANIZER),
                    user(51, "irene", "Ncube", User.Role.EVENT_ORGANIZER),
                    user(52, "Zodwa", "Ncube", User.Role.EVENT_ORGANIZER))))
                    .containsExactly(51L, 50L, 52L);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    @DisplayName("the sort key: stripped, lower-cased, and null for a blank name")
    void nameKey() {
        assertThat(AdminUserOrder.nameKey("  Rumbi ")).isEqualTo("rumbi");
        assertThat(AdminUserOrder.nameKey("")).isNull();
        assertThat(AdminUserOrder.nameKey("   ")).isNull();
        assertThat(AdminUserOrder.nameKey(null)).isNull();
    }
}
