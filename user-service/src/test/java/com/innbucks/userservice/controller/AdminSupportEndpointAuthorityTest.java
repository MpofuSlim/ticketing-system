package com.innbucks.userservice.controller;

import com.innbucks.userservice.devicesecurity.web.AdminDeviceSecurityController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission each customer-support handler is gated on, pinned by
 * reflection — the MockMvc harnesses run controllers without method security,
 * so nothing else fails if a {@code @PreAuthorize} is dropped or points at the
 * wrong code. Also: the search takes its query in the BODY (a POST), never a
 * URL, and the device-security reads keep their shipped paths.
 */
class AdminSupportEndpointAuthorityTest {

    private static Method method(Class<?> type, String name) {
        List<Method> matches = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).toList();
        assertThat(matches).as("%s.%s", type.getSimpleName(), name).hasSize(1);
        return matches.get(0);
    }

    private static String preAuthorize(String name) {
        PreAuthorize a = method(AdminSupportController.class, name).getAnnotation(PreAuthorize.class);
        assertThat(a).as("@PreAuthorize on %s", name).isNotNull();
        return a.value();
    }

    @Test
    @DisplayName("each /admin/support handler is gated on exactly its permission")
    void everyHandlerIsGated() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("searchCustomers", "hasAnyAuthority('support-console:read', 'device-security:read')");
        expected.put("consoleUser", "hasAuthority('support-console:read')");
        expected.put("unlock", "hasAuthority('support-console:manage')");
        expected.put("sendPasswordReset", "hasAuthority('support-console:manage')");
        expected.put("resetMfa", "hasAuthority('support-console:mfa:reset')");
        expected.forEach((m, v) -> assertThat(preAuthorize(m)).as(m).isEqualTo(v));

        long publicHandlers = Arrays.stream(AdminSupportController.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).count();
        assertThat(publicHandlers).as("a new public handler must be added here, gated").isEqualTo(expected.size());
    }

    @Test
    @DisplayName("the search is a POST with the query in the body — customer PII never rides a URL")
    void searchIsAPost() {
        assertThat(AdminSupportController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/admin/support");
        PostMapping post = method(AdminSupportController.class, "searchCustomers").getAnnotation(PostMapping.class);
        assertThat(post.value()).containsExactly("/customers/search");
        assertThat(method(AdminSupportController.class, "consoleUser").getAnnotation(GetMapping.class).value())
                .containsExactly("/console-users/{id}");
    }

    @Test
    @DisplayName("the device-security reads keep their permissions and paths (contract unchanged)")
    void deviceSecurityReadsUnchanged() {
        Map<String, String> expected = Map.of(
                "overview", "hasAuthority('device-security:read')",
                "events", "hasAuthority('device-security:read')",
                "bySupportRef", "hasAuthority('device-security:read')",
                "stopped", "hasAuthority('device-security:read')",
                "sameHandset", "hasAuthority('device-security:fraud')");
        expected.forEach((m, v) -> assertThat(method(AdminDeviceSecurityController.class, m)
                .getAnnotation(PreAuthorize.class).value()).as(m).isEqualTo(v));
    }
}
