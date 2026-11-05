package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Belonging to an organization is business authority (V39), so it requires
 * the second factor exactly as a staff role does.
 *
 * <p>Without this, a plain CUSTOMER added to a business would carry its
 * authority (its listings, and its payout destination) on a session that never
 * passed 2FA. It fails CLOSED here.
 */
class MfaPolicyOrganizationTest {

    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final MfaPolicy policy = new MfaPolicy();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(policy, "organizationMembers", members);
    }

    private static User account(long id, User.Role... roles) {
        return User.builder().id(id).roles(User.roleNames(roles)).mfaEnabled(false).build();
    }

    @Test
    @DisplayName("a customer who works for a business must pass 2FA")
    void customerMemberRequiresMfa() {
        User cashier = account(1, User.Role.CUSTOMER);
        when(members.existsByUserId(1L)).thenReturn(true);

        assertThat(policy.required(cashier, AuthChannel.WEB)).isTrue();
        assertThat(policy.shouldChallenge(cashier, AuthChannel.WEB)).isTrue();
    }

    @Test
    @DisplayName("a customer who works for nobody is unchanged: no 2FA required")
    void plainCustomerUnchanged() {
        User shopper = account(2, User.Role.CUSTOMER);
        when(members.existsByUserId(2L)).thenReturn(false);

        assertThat(policy.required(shopper, AuthChannel.WEB)).isFalse();
    }

    @Test
    @DisplayName("USSD and WhatsApp stay out of scope, member or not")
    void nonApplicableChannelsUnchanged() {
        User cashier = account(5, User.Role.CUSTOMER);
        when(members.existsByUserId(5L)).thenReturn(true);

        assertThat(policy.required(cashier, AuthChannel.USSD)).isFalse();
    }
}
