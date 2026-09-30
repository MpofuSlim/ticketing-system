package com.innbucks.userservice.testsupport;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.StaffEmailPolicy;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.service.StaffEligibility;

import java.util.List;

/** Staff-account (V44) collaborators for plain unit tests, configured like the test profile. */
public final class StaffFixtures {

    public static final List<String> DOMAINS = List.of("innbucks.co.zw", "innbucks.co.ke");

    private StaffFixtures() {
    }

    /** Both staff domains, the default denylist, the production console URL — validated. */
    public static StaffAccountProperties properties() {
        StaffAccountProperties properties = new StaffAccountProperties();
        properties.setAllowedEmailDomains(DOMAINS);
        properties.setConsoleBaseUrl("https://foundry.innbucks.co.zw");
        properties.afterPropertiesSet();
        return properties;
    }

    public static StaffEmailPolicy emailPolicy() {
        return new StaffEmailPolicy(properties());
    }

    public static StaffEligibility eligibility(StaffProfileRepository profiles, RoleGrantGuard guard,
                                               OrganizationMemberRepository members,
                                               OrganizationRepository organizations,
                                               RoleRepository roles, UserRepository users) {
        return new StaffEligibility(profiles, emailPolicy(), guard, members, organizations, roles, users);
    }
}
