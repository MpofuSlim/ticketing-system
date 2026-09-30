package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CountersView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CustomerOverview;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ProfileView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppPhoneView;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3: the LIVE section — {@link InnbucksAppSupportSection#section}, the path a
 * search actually takes, not just the static mask helper — hands out no address
 * whole and no risk feature at all.
 */
class InnbucksAppSupportSectionTest {

    @Test
    @DisplayName("section(): a device's lastIp and an event's address are masked; the event's features are dropped")
    void theLiveSectionMasks() {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC);
        UUID device = UUID.randomUUID();
        SupportDeviceView d = new SupportDeviceView(device, "+263771234567", "Samsung", "android", "15", "SM",
                "samsung", "2.4.0", "TRUSTED", null, null, null, null, false, null, t, t, null, t, t, t, "Harare",
                "41.221.147.12", null, null, 0, "Nothing to do.");
        EventView e = new EventView(1L, t, "SIGN_IN_DECISION", "Sign-in check: TOKEN", "TOKEN", "TOKEN", null, null,
                "APP", null, null, "SIGN_IN", "SIGN_IN", device, null, "196.4.80.9", 0,
                Map.of("ip", "196.4.80.9", "lat", -17.8), null);
        DeviceSupportService dtx = mock(DeviceSupportService.class);
        when(dtx.overview("+263771234567")).thenReturn(new CustomerOverview("+263771234567",
                new ProfileView(null, false, null, null, null, null), List.of(d), new CountersView(0, 0, 0, 0),
                List.of(e), "1 phone signed in."));
        InnbucksAppSupportSection section = new InnbucksAppSupportSection(dtx, mock(CustomerProfileRepository.class));

        SectionView<InnbucksAppSectionData> view = section.section(Map.of("+263771234567", Optional.empty()), "phone");

        assertThat(view.status()).isEqualTo("OK");
        InnbucksAppPhoneView phone = view.data().phones().get(0);
        assertThat(phone.deviceSecurity().devices().get(0).lastIp()).isEqualTo("41.221.x.x");
        assertThat(phone.deviceSecurity().recentEvents().get(0).ipAddress()).isEqualTo("196.4.x.x");
        assertThat(phone.deviceSecurity().recentEvents().get(0).features()).isNull();
        // The customer's own number stays whole; no address survives anywhere in the section.
        assertThat(phone.msisdn()).isEqualTo("+263771234567");
        assertThat(view.toString()).doesNotContain("41.221.147.12").doesNotContain("196.4.80.9");
    }
}
