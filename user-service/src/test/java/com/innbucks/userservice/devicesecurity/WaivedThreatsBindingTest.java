package com.innbucks.userservice.devicesecurity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DEVICE_SECURITY_WAIVED_THREATS} is typed by hand on a box, so the spellings
 * an operator will actually use must bind, and blank must mean "waive nothing".
 */
class WaivedThreatsBindingTest {

    private static DeviceSecurityProperties.Risk bind(String value) {
        return new Binder(new MapConfigurationPropertySource(Map.of("device-security.risk.waived-threats", value)))
                .bind("device-security.risk", DeviceSecurityProperties.Risk.class)
                .orElseGet(DeviceSecurityProperties.Risk::new);
    }

    @Test
    @DisplayName("TAMPER, lower case and a comma list all bind; blank waives nothing")
    void spellings() {
        assertThat(bind("TAMPER").getWaivedThreats()).containsExactly(IntegrityThreat.TAMPER);
        assertThat(bind("tamper").getWaivedThreats()).containsExactly(IntegrityThreat.TAMPER);
        assertThat(bind("TAMPER,HOOKS").getWaivedThreats())
                .containsExactlyInAnyOrder(IntegrityThreat.TAMPER, IntegrityThreat.HOOKS);
        assertThat(bind("").getWaivedThreats()).isEmpty();
        assertThat(new DeviceSecurityProperties.Risk().getWaivedThreats()).isEmpty();
    }
}
