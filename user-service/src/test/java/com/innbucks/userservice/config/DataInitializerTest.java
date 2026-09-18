package com.innbucks.userservice.config;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.BootstrapAdminEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the adoption contract of {@link DataInitializer}: the seeder confers
 * SUPER_ADMIN on an account it did not create ONLY when that account is the
 * legacy platform-admin row — never on one some other caller parked at
 * {@code BOOTSTRAP_ADMIN_EMAIL}.
 *
 * <p>The branch this guards used to MERGE SUPER_ADMIN into whatever roles the
 * existing row held, force it active + approved, and leave its password alone.
 * A row an organizer created through {@code TeamMemberService} (temporary
 * password relayed to that organizer) would come out of the next boot as
 * {@code {SUPER_ADMIN, TEAM_MEMBER}} — privileged, with a password its creator
 * knows and no forced rotation. Reachable through a rotated
 * BOOTSTRAP_ADMIN_EMAIL or a deleted admin row, not by an organizer acting
 * alone against a steady-state cell, but a silent privilege mint either way.
 */
class DataInitializerTest {

    private static final String ADMIN_EMAIL = BootstrapAdminEmail.DEFAULT_ADDRESS;

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private Environment environment;
    private DataInitializer initializer;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        environment = mock(Environment.class);
        initializer = new DataInitializer(userRepository, passwordEncoder, environment);
        // @Value fields are not populated by a plain `new`.
        ReflectionTestUtils.setField(initializer, "adminEmail", ADMIN_EMAIL);
        ReflectionTestUtils.setField(initializer, "adminPassword", "a-real-bootstrap-password");
        ReflectionTestUtils.setField(initializer, "deploymentCountry", "ZW");
    }

    private User rowAt(String email) {
        return User.builder().id(7L).email(email).password("$2a$10$organizer-chosen-hash").build();
    }

    // --- refusal ------------------------------------------------------------

    @Test
    void refusesToAdoptATeamMemberRowParkedAtTheAdminAddress() {
        User teamMember = rowAt(ADMIN_EMAIL);
        teamMember.setRoles(new LinkedHashSet<>(Set.of(User.Role.TEAM_MEMBER.name())));
        teamMember.setCreatedByOrganizerUuid(UUID.randomUUID());
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(teamMember));

        initializer.run();

        // The whole point: no SUPER_ADMIN, and the row is not written at all.
        assertThat(teamMember.getRoles()).containsExactly(User.Role.TEAM_MEMBER.name());
        assertThat(teamMember.hasRole(User.Role.SUPER_ADMIN)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusalDoesNotForceTheRowActiveOrApproved() {
        // The old branch set active+approved unconditionally, so a disabled or
        // unapproved account at this address came back to life as well as
        // gaining the role.
        User teamMember = rowAt(ADMIN_EMAIL);
        teamMember.setRoles(new LinkedHashSet<>(Set.of(User.Role.TEAM_MEMBER.name())));
        teamMember.setActive(false);
        teamMember.setApproved(false);
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(teamMember));

        initializer.run();

        assertThat(teamMember.isActive()).isFalse();
        assertThat(teamMember.isApproved()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesACustomerRow() {
        // A CUSTOMER carries none of the staff stamps — it is caught by the
        // role check alone, which is why that check is the load-bearing one.
        User customer = rowAt(ADMIN_EMAIL);
        customer.setRoles(new LinkedHashSet<>(Set.of(User.Role.CUSTOMER.name())));
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(customer));

        initializer.run();

        assertThat(customer.hasRole(User.Role.SUPER_ADMIN)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesARowStampedByAnOrganizerEvenWithNoRoles() {
        User stamped = rowAt(ADMIN_EMAIL);
        stamped.setRoles(new LinkedHashSet<>());
        stamped.setCreatedByOrganizerUuid(UUID.randomUUID());
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(stamped));

        initializer.run();

        assertThat(stamped.hasRole(User.Role.SUPER_ADMIN)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesLoyaltyScopedShopStaff() {
        // ShopStaffService stamps merchant/shop ids rather than an organizer
        // uuid, so the stamp check has to cover both shapes.
        User shopStaff = rowAt(ADMIN_EMAIL);
        shopStaff.setRoles(new LinkedHashSet<>());
        shopStaff.setLoyaltyMerchantId(UUID.randomUUID());
        shopStaff.setLoyaltyShopId(UUID.randomUUID());
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(shopStaff));

        initializer.run();

        assertThat(shopStaff.hasRole(User.Role.SUPER_ADMIN)).isFalse();
        verify(userRepository, never()).save(any());
    }

    // --- adoption (the legitimate legacy-admin path) ------------------------

    @Test
    void adoptsTheLegacyAdminRowAndForcesAPasswordChange() {
        // The case the migration branch exists for: an admin row created before
        // the join tables, so no roles and no stamps. Adoptable — but the
        // password on it was not chosen by this seeder, so it must be rotated
        // before it can be used against the privilege just granted.
        User legacy = rowAt(ADMIN_EMAIL);
        legacy.setRoles(new LinkedHashSet<>());
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(legacy));

        initializer.run();

        assertThat(legacy.getRoles()).containsExactly(User.Role.SUPER_ADMIN.name());
        assertThat(legacy.isMustChangePassword()).isTrue();
        assertThat(legacy.isActive()).isTrue();
        assertThat(legacy.isApproved()).isTrue();
        verify(userRepository).save(legacy);
    }

    @Test
    void doesNotReArmMustChangePasswordOnAnAlreadyAdminRow() {
        // Guards against the flag firing on every restart, which would lock the
        // admin into changing their password at each boot.
        User admin = rowAt(ADMIN_EMAIL);
        admin.setRoles(new LinkedHashSet<>(Set.of(User.Role.SUPER_ADMIN.name())));
        admin.setActive(true);
        admin.setApproved(true);
        admin.setMustChangePassword(false);
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(admin));

        initializer.run();

        assertThat(admin.isMustChangePassword()).isFalse();
    }

    @Test
    void seedsAFreshAdminWhenNoRowExists() {
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("HASHED");

        initializer.run();

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoles()).containsExactly(User.Role.SUPER_ADMIN.name());
        assertThat(saved.getValue().isMustChangePassword()).isTrue();
    }
}
