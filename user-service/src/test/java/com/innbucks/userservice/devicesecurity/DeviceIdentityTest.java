package com.innbucks.userservice.devicesecurity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIdentityTest {

    @Test
    void destinationMasked_showsCountryFirstTwoAndLastTwo() {
        assertThat(DeviceIdentity.destinationMasked("+263771234512")).isEqualTo("+263 77 *** **12");
        assertThat(DeviceIdentity.destinationMasked("+254712345678")).isEqualTo("+254 71 *** **78");
    }

    @Test
    void logMask_keepsOnlyTheLastTwoDigits() {
        assertThat(DeviceIdentity.logMask("+263771234512")).isEqualTo("********12");
        assertThat(DeviceIdentity.logMask(null)).isEqualTo("**");
    }

    @Test
    void installIdHash_isStableAndCaseInsensitive() {
        String a = DeviceIdentity.hashInstallId("8B1E6C0E-4F2A-4D8E-9B3C-1A2B3C4D5E6F");
        assertThat(a).hasSize(64).isEqualTo(DeviceIdentity.hashInstallId(" 8b1e6c0e-4f2a-4d8e-9b3c-1a2b3c4d5e6f "));
        assertThat(a).doesNotContain("8b1e");
    }

    @Test
    void labels() {
        assertThat(DeviceIdentity.shortLabel("samsung", "SM-A155F", "android")).isEqualTo("Samsung SM-A155F");
        assertThat(DeviceIdentity.shortLabel("Apple", "iPhone 15", "ios")).isEqualTo("Apple iPhone 15");
        assertThat(DeviceIdentity.shortLabel("TECNO", "TECNO KL4", "android")).isEqualTo("TECNO KL4");
        assertThat(DeviceIdentity.shortLabel(null, null, "android")).isEqualTo("Android phone");
        assertThat(DeviceIdentity.fullLabel("samsung", "SM-A155F", "android", "15")).isEqualTo("Samsung SM-A155F · Android 15");
    }

    @Test
    void supportRefs_areReadableAndForgiving() {
        String ref = SupportRefs.next();
        assertThat(ref).matches("SEC-[0-9A-HJKMNP-TV-Z]{6}");
        assertThat(SupportRefs.normalise("sec-8f2kq7")).isEqualTo("SEC-8F2KQ7");
        assertThat(SupportRefs.normalise("8F2KQ7")).isEqualTo("SEC-8F2KQ7");
        assertThat(SupportRefs.normalise("SEC 8F2KQO")).isEqualTo("SEC-8F2KQ0");
    }

    @Test
    void integrityThreats_recogniseFreeRaspNames() {
        assertThat(IntegrityThreat.classify(java.util.List.of("privilegedAccess", "hooks", "appIntegrity",
                "automation", "malware", "simulator", "debug", "unofficialStore", "somethingNew")))
                .containsExactlyInAnyOrder(IntegrityThreat.values());
    }
}
