package com.innbucks.userservice.devicesecurity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One phone per number is switched on per host by {@code DEVICE_SECURITY_SINGLE_DEVICE},
 * and ships OFF. Bound through the real {@code application.yaml} (its placeholder and
 * default), so a renamed env var or a flipped default fails here rather than on a cell
 * that silently keeps — or silently starts — signing phones out.
 */
class SingleDeviceSwitchBindingTest {

    private static final String KEY = "device-security.trust.single-device";

    /** The application.yaml document that carries the device-security block. */
    private static PropertySource<?> applicationYaml() {
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load("application.yaml", new ClassPathResource("application.yaml"));
            return documents.stream().filter(d -> d.containsProperty(KEY)).findFirst()
                    .orElseThrow(() -> new AssertionError("application.yaml has no " + KEY));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Binder binder(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(env)));
        sources.addLast(applicationYaml());
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    private static DeviceSecurityProperties.Trust trust(Map<String, Object> env) {
        return binder(env).bind("device-security.trust", DeviceSecurityProperties.Trust.class)
                .orElseThrow(() -> new AssertionError("device-security.trust did not bind"));
    }

    @Test
    @DisplayName("application.yaml maps DEVICE_SECURITY_SINGLE_DEVICE=true onto trust.single-device")
    void envVarTrue_switchesItOn() {
        assertThat(trust(Map.of("DEVICE_SECURITY_SINGLE_DEVICE", "true")).isSingleDevice()).isTrue();
        assertThat(binder(Map.of("DEVICE_SECURITY_SINGLE_DEVICE", "true")).bind(KEY, Boolean.class).get()).isTrue();
    }

    @Test
    @DisplayName("DEVICE_SECURITY_SINGLE_DEVICE=false keeps it off")
    void envVarFalse_keepsItOff() {
        assertThat(trust(Map.of("DEVICE_SECURITY_SINGLE_DEVICE", "false")).isSingleDevice()).isFalse();
    }

    @Test
    @DisplayName("with no DEVICE_SECURITY_SINGLE_DEVICE the yaml default is off, and so is the class default")
    void absent_defaultsToOff() {
        assertThat(trust(Map.of()).isSingleDevice()).isFalse();
        assertThat(binder(Map.of()).bind(KEY, Boolean.class).get()).isFalse();
        assertThat(new DeviceSecurityProperties().getTrust().isSingleDevice()).isFalse();
        assertThat(new DeviceSecurityProperties.Trust().isSingleDevice()).isFalse();
    }
}
