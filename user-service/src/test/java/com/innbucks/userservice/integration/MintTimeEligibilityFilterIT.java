package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.StaffMintFilter;
import com.innbucks.userservice.testsupport.StaffItSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mint-time eligibility filter over real sign-ins (V44 §2.9):
 * {@code watch} mints as before and counts; {@code enforce} withholds every
 * PLATFORM code and every NAMED staff role name (booking and event grant by
 * name) from a holder who is not staff-eligible; SUPER_ADMIN and an eligible
 * staff account are untouched in either mode.
 */
class MintTimeEligibilityFilterIT extends StaffItSupport {

    @Autowired StaffAccountProperties staffProperties;
    @Autowired MeterRegistry meters;

    @AfterEach
    void backToWatch() {
        staffProperties.setEligibilityEnforcement(StaffAccountProperties.Enforcement.WATCH);
    }

    private User enrolled(String address, String role) {
        return users.save(User.builder().firstName("Farai").lastName("Ncube").email(address).phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD)).roles(User.roleNames(User.Role.valueOf(role)))
                .active(true).approved(true).mfaEnabled(true).mfaSecret(TOTP_SECRET).build());
    }

    private double offDomainCount() {
        Counter c = meters.find(StaffMintFilter.METRIC).tag("reason", "off_domain").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void watchCounts_enforceWithholds_superAdminAndEligibleStaffUntouched() throws Exception {
        User offDomain = enrolled("po-" + unique() + "@gmail.com", "PRODUCT_OFFICER");
        User owner = enrolled("owner-" + unique() + "@example.com", "SUPER_ADMIN");
        User agent = eligibleStaff("CALL_CENTER_AGENT", true);

        // watch (the default): minted exactly as before, and counted.
        double before = offDomainCount();
        JsonNode watched = claims(signIn(offDomain.getEmail()).at("/token").asText());
        assertThat(strings(watched.get("roles"))).containsExactly("PRODUCT_OFFICER");
        assertThat(strings(watched.get("perms"))).isNotEmpty();
        assertThat(offDomainCount()).isEqualTo(before + 1);

        // enforce: the NAMED role and every PLATFORM code are withheld.
        staffProperties.setEligibilityEnforcement(StaffAccountProperties.Enforcement.ENFORCE);
        JsonNode session = signIn(offDomain.getEmail());
        JsonNode enforced = claims(session.at("/token").asText());
        assertThat(strings(enforced.get("roles"))).doesNotContain("PRODUCT_OFFICER");
        assertThat(strings(enforced.get("perms"))).isEmpty();
        assertThat(strings(session.get("permissions"))).isEmpty();

        // SUPER_ADMIN: exempt, keeps the full catalog (including the staff codes).
        JsonNode superAdmin = claims(signIn(owner.getEmail()).at("/token").asText());
        assertThat(strings(superAdmin.get("roles"))).containsExactly("SUPER_ADMIN");
        assertThat(strings(superAdmin.get("perms"))).contains("staff:create", "staff:manage", "users:read");

        // An eligible staff account keeps its authority.
        JsonNode staff = claims(signIn(agent.getEmail()).at("/token").asText());
        assertThat(strings(staff.get("roles"))).containsExactly("CALL_CENTER_AGENT");
        assertThat(strings(staff.get("perms"))).containsExactlyInAnyOrder("device-security:read",
                "device-security:manage");
    }
}
