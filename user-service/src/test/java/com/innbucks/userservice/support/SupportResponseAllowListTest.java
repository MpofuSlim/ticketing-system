package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CountersView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CustomerOverview;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ProfileView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import com.innbucks.userservice.support.dto.SupportDTOs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The never-shown and masking rules (design §3.1.4) for everything this
 * release's support surface returns. Every support response is an ALLOW-LIST of
 * record components, so the test pins them by name: adding a component forces
 * someone to look at this list, and a secret-shaped name anywhere fails outright.
 */
class SupportResponseAllowListTest {

    /** No component of any support shape may be named like a secret. */
    private static final List<String> FORBIDDEN = List.of("password", "secret", "otp", "pin", "token", "backup",
            "install", "hash", "qr", "vouchercode", "collectioncode", "paymentcode", "innbuckscode", "ticketnumber",
            "customeraccount", "nationalid");

    @Test
    @DisplayName("no support response component is named like a secret (password, secret, OTP, PIN, token, install id…)")
    void noSecretShapedComponents() {
        for (Class<?> type : SupportDTOs.class.getDeclaredClasses()) {
            if (!type.isRecord()) continue;
            for (RecordComponent rc : type.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                // A boolean saying a temporary password must be replaced — not a password.
                if ("mustchangepassword".equals(name)) continue;
                for (String bad : FORBIDDEN) {
                    // pinIssuedAt is a timestamp in the reused DTX profile, not here.
                    assertThat(name).as("%s.%s", type.getSimpleName(), rc.getName()).doesNotContain(bad);
                }
            }
        }
        // The reused DTX device view carries no install id either.
        assertThat(names(SupportDeviceView.class)).noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("install"));
    }

    @Test
    @DisplayName("the console account view is exactly this allow-list")
    void consoleAccountAllowList() {
        assertThat(names(SupportDTOs.ConsoleAccountView.class)).containsExactly(
                "userId", "userUuid", "name", "email", "phone", "status", "roles", "staffAccount", "mfaEnrolled",
                "lockedUntil", "mfaLockedUntil", "failedSignInAttempts", "lastSignInAt", "mustChangePassword",
                "createdAt", "organizations", "serviceRequests", "agentGuidance", "actions");
        // A staff account is a stub: that it is one, whom to ask — nothing that describes it.
        assertThat(names(SupportDTOs.StaffAccountStub.class)).containsExactly("staffAccount", "agentGuidance", "actions");
        assertThat(SupportDTOs.ConsoleAccountEntry.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(SupportDTOs.ConsoleAccountView.class, SupportDTOs.StaffAccountStub.class);
        // No organization contact details, and no member list (the OWNERs an MFA reset notifies are never shown).
        assertThat(names(SupportDTOs.OrganizationView.class))
                .containsExactly("organizationId", "name", "role", "status", "products");
        assertThat(names(SupportDTOs.ServiceRequestView.class)).containsExactly(
                "id", "service", "status", "submittedAt", "decidedAt", "decisionReason", "guidance");
    }

    @Test
    @DisplayName("the InnBucks app view is the DTX overview plus the four customer_profiles facts — no KYC, no national id")
    void innbucksAppAllowList() {
        assertThat(names(SupportDTOs.CustomerProfileView.class))
                .containsExactly("registrationTier", "verified", "phoneVerified", "phoneVerifiedAt");
        assertThat(names(SupportDTOs.InnbucksAppPhoneView.class))
                .containsExactly("msisdn", "customerProfile", "deviceSecurity");
    }

    @Test
    @DisplayName("the section masks every address and drops the risk features; the customer's own number stays whole")
    void innbucksAppSectionMasks() {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC);
        UUID device = UUID.randomUUID();
        SupportDeviceView d = new SupportDeviceView(device, "+263771234567", "Samsung", "android", "15", "SM", "samsung",
                "2.4.0", "TRUSTED", null, null, null, null, false, null, t, t, null, t, t, t, "Harare",
                "41.221.147.12", null, null, 0, "Nothing to do.");
        EventView e = new EventView(1L, t, "SIGN_IN_DECISION", "Sign-in check: TOKEN", "TOKEN", "TOKEN", null, null,
                "APP", null, null, "SIGN_IN", "SIGN_IN", device, null, "2c0f:f4c0:1:2::7", 0,
                Map.of("ip", "41.221.147.12", "lat", -17.8), null);
        CustomerOverview o = new CustomerOverview("+263771234567",
                new ProfileView(null, false, null, null, null, null), List.of(d), new CountersView(0, 0, 0, 0),
                List.of(e), "1 phone signed in.");

        SupportDTOs.DeviceSecurityView masked = InnbucksAppSupportSection.mask(o);

        assertThat(masked.devices().get(0).lastIp()).isEqualTo("41.221.x.x");
        assertThat(masked.devices().get(0).msisdn()).isEqualTo("+263771234567");
        assertThat(masked.recentEvents().get(0).ipAddress()).isEqualTo("2c0f:f4c0:…");
        assertThat(masked.recentEvents().get(0).features()).isNull();
        assertThat(masked.summary()).isEqualTo("1 phone signed in.");
    }

    @Test
    @DisplayName("masks: phone to its last 4, email to first letter + domain, IP to its network half")
    void masks() {
        assertThat(SupportMasking.phone("+263771234567")).isEqualTo("****4567");
        assertThat(SupportMasking.email("tariro@example.com")).isEqualTo("t****@example.com");
        assertThat(SupportMasking.email("nonsense")).isEqualTo("****");
        assertThat(SupportMasking.email(null)).isEqualTo("****");
        assertThat(SupportMasking.ip("41.221.147.12")).isEqualTo("41.221.x.x");
        assertThat(SupportMasking.ip("2c0f:f4c0::1")).isEqualTo("2c0f:f4c0:…");
        assertThat(SupportMasking.ip("::1")).isEqualTo("…");
        assertThat(SupportMasking.ip("garbage")).isEqualTo("…");
        assertThat(SupportMasking.ip(null)).isNull();
        assertThat(SupportMasking.customerKey("+263771234567", "tariro@example.com")).isEqualTo("msisdn:****4567");
        assertThat(SupportMasking.customerKey(null, "tariro@example.com")).isEqualTo("email:t****@example.com");
    }

    @Test
    @DisplayName("the access log's query_masked never holds a phone or email whole")
    void queryMasked() {
        SupportQueryClassifier c = new SupportQueryClassifier("ZW");
        assertThat(SupportMasking.query(c.classify("+263771234567"))).isEqualTo("****4567");
        assertThat(SupportMasking.query(c.classify("tariro@example.com"))).isEqualTo("t****@example.com");
        assertThat(SupportMasking.query(c.classify("sec-8f2kq7"))).isEqualTo("SEC-8F2KQ7");
    }

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
