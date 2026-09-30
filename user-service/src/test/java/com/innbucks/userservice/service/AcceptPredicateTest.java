package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accept-eligible (V44) is a SEPARATE predicate from staff-eligible: it does not
 * require the email to be proven, because redeeming the invite is what proves it.
 */
class AcceptPredicateTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private User invited(String email) {
        User u = h.account(email, "CALL_CENTER_AGENT");
        h.profileRows.put(u.getId(), StaffProfile.builder().userId(u.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        return u;
    }

    @Test
    @DisplayName("passes for a never-verified profiled account (verification is what accepting establishes)")
    void passesUnverified() {
        User u = invited("tariro.moyo@innbucks.co.zw");
        assertThat(u.getEmailVerifiedAt()).isNull();
        assertThat(h.eligibility.acceptIneligibility(u, "tariro.moyo@innbucks.co.zw")).isEmpty();
        // ...while the same account is NOT staff-eligible yet.
        assertThat(h.eligibility.ineligibility(u)).contains(StaffEligibility.Ineligibility.UNVERIFIED);
    }

    @Test
    @DisplayName("fails when the account's email is no longer the address the invite was sent to")
    void emailChanged() {
        User u = invited("tariro.moyo@innbucks.co.zw");
        assertThat(h.eligibility.acceptIneligibility(u, "t.moyo@innbucks.co.zw")).contains("email_changed");
    }

    @Test
    @DisplayName("fails on an ACTIVE organization membership")
    void activeOrganization() {
        User u = invited("tariro.moyo@innbucks.co.zw");
        h.organizationOf(u, OrganizationMember.Role.OWNER);
        assertThat(h.eligibility.acceptIneligibility(u, u.getEmail())).contains("organization_member");
    }

    @Test
    @DisplayName("fails on an address a config change took off the staff domains")
    void offDomainAfterConfigChange() {
        User u = invited("tariro.moyo@innbucks.co.zw");
        h.properties.setAllowedEmailDomains(List.of("innbucks.co.ke"));
        h.properties.afterPropertiesSet();
        assertThat(h.eligibility.acceptIneligibility(u, u.getEmail())).contains("off_domain");
    }

    @Test
    @DisplayName("fails when inactive, already accepted, unprofiled, or holding a business role")
    void otherRefusals() {
        User inactive = invited("inactive@innbucks.co.zw");
        inactive.setActive(false);
        assertThat(h.eligibility.acceptIneligibility(inactive, inactive.getEmail())).contains("account_inactive");

        User accepted = invited("accepted@innbucks.co.zw");
        h.profileRows.get(accepted.getId()).setInviteAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(h.eligibility.acceptIneligibility(accepted, accepted.getEmail())).contains("already_accepted");

        User unprofiled = h.account("legacy@innbucks.co.zw", "CALL_CENTER_AGENT");
        assertThat(h.eligibility.acceptIneligibility(unprofiled, unprofiled.getEmail())).contains("no_profile");

        User mixed = invited("mixed@innbucks.co.zw");
        mixed.getRoles().add("MERCHANT_ADMIN");
        assertThat(h.eligibility.acceptIneligibility(mixed, mixed.getEmail())).contains("holds_non_staff_roles");
    }
}
