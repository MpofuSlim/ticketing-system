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
 * The Super app's SMS Retriever hash is set per host by {@code DEVICE_SECURITY_SMS_APP_HASH}
 * and ships blank. Bound through the real {@code application.yaml}, so a renamed env var
 * fails here rather than on a host whose codes silently stop carrying the hash.
 */
class SmsAppHashBindingTest {

    private static final String KEY = "device-security.otp.sms-app-hash";

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

    private static DeviceSecurityProperties.Otp otp(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(env)));
        sources.addLast(applicationYaml());
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("device-security.otp", DeviceSecurityProperties.Otp.class)
                .orElseThrow(() -> new AssertionError("device-security.otp did not bind"));
    }

    @Test
    @DisplayName("application.yaml maps DEVICE_SECURITY_SMS_APP_HASH onto otp.sms-app-hash")
    void envVar_isBound() {
        assertThat(otp(Map.of("DEVICE_SECURITY_SMS_APP_HASH", "GcOlMYQc0Wf")).getSmsAppHash()).isEqualTo("GcOlMYQc0Wf");
    }

    @Test
    @DisplayName("with no DEVICE_SECURITY_SMS_APP_HASH there is no hash, so SMS codes stay one line")
    void absent_isBlank() {
        String hash = otp(Map.of()).getSmsAppHash();
        assertThat(hash == null || hash.isBlank()).as("[" + hash + "]").isTrue();
        assertThat(new DeviceSecurityProperties().getOtp().getSmsAppHash()).isNull();
    }
}
