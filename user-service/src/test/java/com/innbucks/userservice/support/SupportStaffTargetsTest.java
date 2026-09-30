package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.StaffEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staff-target detection (design §3.1.2) and the self-action rule (§3.1.5 step
 * 3): which customer keys reach an InnBucks staff account, and which are the
 * agent's own.
 */
class SupportStaffTargetsTest {

    private UserRepository users;
    private StaffProfileRepository profiles;
    private StaffEligibility eligibility;
    private SupportStaffTargets targets;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(StaffProfileRepository.class);
        eligibility = mock(StaffEligibility.class);
        targets = new SupportStaffTargets(users, profiles, eligibility);
        when(users.findAllByEmailIgnoreCase(any())).thenReturn(List.of());
        when(users.findByPhoneNumber(any())).thenReturn(Optional.empty());
        when(users.findByUserUuidIn(anyCollection())).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());
        when(profiles.findAllByContactPhoneIn(anyCollection())).thenReturn(List.of());
    }

    private static User user(long id) {
        return User.builder().id(id).userUuid(UUID.randomUUID()).build();
    }

    private static SupportCustomerKeys keys(List<String> phones, List<String> emails) {
        return new SupportCustomerKeys(phones, emails, List.of(), List.of(), null);
    }

    @Test
    @DisplayName("a staff users.email matches (case-insensitively)")
    void staffEmail() {
        User staff = user(5);
        when(users.findAllByEmailIgnoreCase("chipo@innbucks.co.zw")).thenReturn(List.of(staff));
        when(eligibility.isStaffAccount(staff)).thenReturn(true);
        assertThat(targets.staffAccounts(keys(List.of(), List.of("Chipo@InnBucks.co.zw"))))
                .containsExactly(staff.getUserUuid());
    }

    @Test
    @DisplayName("a staff CONTACT phone matches even though it is no sign-in phone")
    void contactPhone() {
        User staff = user(6);
        when(profiles.findAllByContactPhoneIn(List.of("+263772000001")))
                .thenReturn(List.of(StaffProfile.builder().userId(6L).contactPhone("+263772000001").build()));
        when(users.findAllById(List.of(6L))).thenReturn(List.of(staff));
        assertThat(targets.staffAccounts(keys(List.of("+263772000001"), List.of())))
                .containsExactly(staff.getUserUuid());
    }

    @Test
    @DisplayName("a legacy staff sign-in phone matches")
    void legacyStaffPhone() {
        User legacy = user(7);
        when(users.findByPhoneNumber("+263772000002")).thenReturn(Optional.of(legacy));
        when(eligibility.isStaffAccount(legacy)).thenReturn(true);
        assertThat(targets.staffAccounts(keys(List.of("+263772000002"), List.of()))).containsExactly(legacy.getUserUuid());
    }

    @Test
    @DisplayName("an ordinary customer or merchant matches nothing")
    void notStaff() {
        User merchant = user(8);
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(merchant));
        when(eligibility.isStaffAccount(merchant)).thenReturn(false);
        assertThat(targets.staffAccounts(keys(List.of("+263771234567"), List.of("tariro@example.com")))).isEmpty();
    }

    @Test
    @DisplayName("self-action: the agent's uuid, id, email, sign-in phone or contact phone in the keys")
    void selfAction() {
        UUID me = UUID.randomUUID();
        SupportAgent agent = new SupportAgent("Agent.One@innbucks.co.zw", 7L, me, "Agent.One@innbucks.co.zw",
                "+263773000003", "+263772000001", Set.of());
        assertThat(agent.isSelf(new SupportCustomerKeys(List.of(), List.of(), List.of(me.toString()), List.of(), null)))
                .isTrue();
        assertThat(agent.isSelf(new SupportCustomerKeys(List.of(), List.of(), List.of(), List.of(7L), null))).isTrue();
        assertThat(agent.isSelf(keys(List.of(), List.of("agent.one@innbucks.co.zw")))).isTrue();
        assertThat(agent.isSelf(keys(List.of("+263773000003"), List.of()))).isTrue();
        assertThat(agent.isSelf(keys(List.of("+263772000001"), List.of()))).isTrue();
        assertThat(agent.isSelf(keys(List.of("+263771234567"), List.of("tariro@example.com")))).isFalse();
        assertThat(agent.isSelf(null)).isFalse();
    }
}
