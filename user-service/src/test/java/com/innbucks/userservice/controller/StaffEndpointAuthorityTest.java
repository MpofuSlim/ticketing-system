package com.innbucks.userservice.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission each staff-administration handler (V44) is gated on, pinned by
 * reflection. The MockMvc harnesses run the controllers standalone, WITHOUT
 * method security, so nothing else fails if a {@code @PreAuthorize} is dropped
 * or pointed at the wrong code — and a wrong code here is a staff-management
 * endpoint open to whoever holds it. All four codes are wildcard-reserved.
 */
class StaffEndpointAuthorityTest {

    private static String preAuthorize(Class<?> controller, String method) {
        List<Method> matches = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(method)).toList();
        assertThat(matches).as("%s.%s", controller.getSimpleName(), method).hasSize(1);
        PreAuthorize annotation = matches.get(0).getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("@PreAuthorize on %s.%s", controller.getSimpleName(), method).isNotNull();
        return annotation.value();
    }

    @Test
    @DisplayName("each /admin/staff handler, and organization suspend, is gated on exactly its permission")
    void everyHandlerIsGated() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("create", "hasAuthority('staff:create')");
        expected.put("list", "hasAuthority('staff:read')");
        expected.put("get", "hasAuthority('staff:read')");
        expected.put("deactivate", "hasAuthority('staff:manage')");
        expected.put("reactivate", "hasAuthority('staff:manage')");
        expected.put("resendInvite", "hasAuthority('staff:create')");
        expected.put("audit", "hasAuthority('staff:read')");
        expected.forEach((method, value) ->
                assertThat(preAuthorize(AdminStaffController.class, method)).as(method).isEqualTo(value));

        assertThat(preAuthorize(AdminOrganizationController.class, "suspend"))
                .isEqualTo("hasAuthority('organizations:manage')");
    }

    @Test
    @DisplayName("no public handler on AdminStaffController is left ungated")
    void noUngatedHandler() {
        Map<String, Boolean> gated = new TreeMap<>();
        for (Method m : AdminStaffController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                gated.put(m.getName(), m.isAnnotationPresent(PreAuthorize.class));
            }
        }
        assertThat(gated).hasSize(7).doesNotContainValue(false);
    }
}
