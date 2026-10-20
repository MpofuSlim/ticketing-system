package com.innbucks.userservice.service;

import com.innbucks.userservice.exception.PosApiKeyException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosIpAllowListTest {

    @Test
    void anEmptyListAllowsAnyAddress_evenAnUnknownOne() {
        PosIpAllowList any = PosIpAllowList.fromColumn(null);
        assertThat(any.allowsAny()).isTrue();
        assertThat(any.allows("196.27.104.10")).isTrue();
        assertThat(any.allows(null)).isTrue();
    }

    @Test
    void exactAddressesAndRanges_v4() {
        PosIpAllowList list = PosIpAllowList.fromColumn("196.27.104.10,41.190.32.0/24");
        assertThat(list.allows("196.27.104.10")).isTrue();
        assertThat(list.allows("196.27.104.11")).isFalse();
        assertThat(list.allows("41.190.32.0")).isTrue();
        assertThat(list.allows("41.190.32.255")).isTrue();
        assertThat(list.allows("41.190.33.1")).isFalse();
    }

    @Test
    void nonByteAlignedRange() {
        PosIpAllowList list = PosIpAllowList.fromColumn("10.0.0.0/12");
        assertThat(list.allows("10.15.255.255")).isTrue();
        assertThat(list.allows("10.16.0.0")).isFalse();
    }

    @Test
    void ipv6Ranges_andNoCrossFamilyMatch() {
        PosIpAllowList list = PosIpAllowList.fromColumn("2001:db8:abcd::/48");
        assertThat(list.allows("2001:db8:abcd:12::1")).isTrue();
        assertThat(list.allows("2001:db8:abce::1")).isFalse();
        assertThat(list.allows("196.27.104.10")).isFalse();
    }

    @Test
    void aRestrictedKeyRefusesAMissingOrGarbledAddress() {
        PosIpAllowList list = PosIpAllowList.fromColumn("196.27.104.10");
        assertThat(list.allows(null)).isFalse();
        assertThat(list.allows("")).isFalse();
        assertThat(list.allows("not-an-ip")).isFalse();
        assertThat(list.allows("196.27.104.10, 6.6.6.6")).isFalse();
    }

    @Test
    void normalise_trimsLowerCasesAndDeduplicates() {
        assertThat(PosIpAllowList.normalise(Arrays.asList(" 196.27.104.10 ", "2001:DB8::/32", "196.27.104.10", "", null)))
                .containsExactly("196.27.104.10", "2001:db8::/32");
    }

    @Test
    void hostnamesAreRefused_soNothingIsEverLookedUp() {
        for (String bad : List.of("gaap.example.com", "face.bad", "localhost", "999.1.1.1", "1.2.3.4/33",
                "1.2.3.4/x", "::1/129")) {
            assertThatThrownBy(() -> PosIpAllowList.normalise(List.of(bad)))
                    .as(bad)
                    .isInstanceOf(PosApiKeyException.class)
                    .extracting("errorCode").isEqualTo("pos_key_bad_ip");
        }
    }
}
